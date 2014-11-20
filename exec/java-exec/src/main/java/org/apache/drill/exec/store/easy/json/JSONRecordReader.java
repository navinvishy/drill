/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.easy.json;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.PathSegment;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.store.AbstractRecordReader;
import org.apache.drill.exec.vector.BaseValueVector;
import org.apache.drill.exec.vector.complex.fn.JsonReader;
import org.apache.drill.exec.vector.complex.impl.VectorContainerWriter;
import org.apache.drill.exec.vector.complex.writer.BaseWriter;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.base.Stopwatch;

public class JSONRecordReader extends AbstractRecordReader {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JSONRecordReader.class);

  private OutputMutator mutator;
  private VectorContainerWriter writer;
  private Path hadoopPath;
  private FileSystem fileSystem;
  private FSDataInputStream stream;
  private JsonReader jsonReader;
  private int recordCount;
  private FragmentContext fragmentContext;
  private OperatorContext operatorContext;
  private List<SchemaPath> columns;
  private boolean enableAllTextMode;

  public JSONRecordReader(FragmentContext fragmentContext, String inputPath, FileSystem fileSystem,
                          List<SchemaPath> columns) throws OutOfMemoryException {
    this.hadoopPath = new Path(inputPath);
    this.fileSystem = fileSystem;
    this.fragmentContext = fragmentContext;
    this.columns = columns;
    this.enableAllTextMode = fragmentContext.getDrillbitContext().getOptionManager().getOption(ExecConstants.JSON_ALL_TEXT_MODE).bool_val;
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    try{
      this.stream = fileSystem.open(hadoopPath);
      this.writer = new VectorContainerWriter(output);
      this.mutator = output;
      this.jsonReader = new JsonReader(fragmentContext.getManagedBuffer(), columns, enableAllTextMode);
      this.jsonReader.setSource(stream);
    }catch(Exception e){
      handleAndRaise("Failure reading JSON file.", e);
    }
  }

  protected void handleAndRaise(String msg, Exception e) {
    StringBuilder sb = new StringBuilder();
    sb.append(msg).append(" - Parser was at record: ").append(recordCount+1);
    if (e instanceof JsonParseException) {
      JsonParseException ex = JsonParseException.class.cast(e);
      sb.append(" column: ").append(ex.getLocation().getColumnNr());
    }
    throw new DrillRuntimeException(sb.toString(), e);
  }


  public OperatorContext getOperatorContext() {
    return operatorContext;
  }

  public void setOperatorContext(OperatorContext operatorContext) {
    this.operatorContext = operatorContext;
  }

  @Override
  public int next() {
    writer.allocate();
    writer.reset();

    recordCount = 0;
//    Stopwatch p = new Stopwatch().start();
    try{
      outside: while(recordCount < BaseValueVector.INITIAL_VALUE_ALLOCATION){
        writer.setPosition(recordCount);
        boolean write = jsonReader.write(writer);

        if(write){
//          logger.debug("Wrote record.");
          recordCount++;
        }else{
//          logger.debug("Exiting.");
          break outside;
        }

      }

      jsonReader.ensureAtLeastOneField(writer);

      writer.setValueCount(recordCount);
//      p.stop();
//      System.out.println(String.format("Wrote %d records in %dms.", recordCount, p.elapsed(TimeUnit.MILLISECONDS)));

      return recordCount;

    } catch (JsonParseException e) {
      handleAndRaise("Error parsing JSON.", e);
    } catch (IOException e) {
      handleAndRaise("Error reading JSON.", e);
    }
    // this is never reached
    return 0;
  }

  @Override
  public void cleanup() {
    try {
      stream.close();
    } catch (IOException e) {
      logger.warn("Failure while closing stream.", e);
    }
  }

}