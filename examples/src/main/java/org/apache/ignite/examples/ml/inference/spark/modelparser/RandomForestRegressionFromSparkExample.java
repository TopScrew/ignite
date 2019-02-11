/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.examples.ml.inference.spark.modelparser;

import java.io.FileNotFoundException;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.examples.ml.tutorial.TitanicUtils;
import org.apache.ignite.ml.composition.ModelsComposition;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.primitives.vector.VectorUtils;
import org.apache.ignite.ml.sparkmodelparser.SparkModelParser;
import org.apache.ignite.ml.sparkmodelparser.SupportedSparkModels;

/**
 * Run Random Forest regression model loaded from snappy.parquet file.
 * The snappy.parquet file was generated by Spark MLLib model.write.overwrite().save(..) operator.
 * <p>
 * You can change the test data used in this example and re-run it to explore this algorithm further.</p>
 */
public class RandomForestRegressionFromSparkExample {
    /** Path to Spark Random Forest regression model. */
    public static final String SPARK_MDL_PATH = "examples/src/main/resources/models/spark/serialized/rfreg/data" +
        "/part-00000-06273895-4b81-4a77-823e-dfd32d1560eb-c000.snappy.parquet";

    /** Run example. */
    public static void main(String[] args) throws FileNotFoundException {
        System.out.println();
        System.out.println(">>> Random Forest regression model loaded from Spark through serialization over partitioned dataset usage example started.");
        // Start ignite grid.
        try (Ignite ignite = Ignition.start("examples/config/example-ignite.xml")) {
            System.out.println(">>> Ignite grid started.");

            IgniteCache<Integer, Object[]> dataCache = TitanicUtils.readPassengers(ignite);

            IgniteBiFunction<Integer, Object[], Vector> featureExtractor = (k, v) -> {
                double[] data = new double[] {(double)v[0], (double)v[1], (double)v[5], (double)v[6]};
                data[0] = Double.isNaN(data[0]) ? 0 : data[0];
                data[1] = Double.isNaN(data[1]) ? 0 : data[1];
                data[2] = Double.isNaN(data[2]) ? 0 : data[2];
                data[3] = Double.isNaN(data[3]) ? 0 : data[3];
                return VectorUtils.of(data);
            };

            IgniteBiFunction<Integer, Object[], Double> lbExtractor = (k, v) -> (double)v[4];

            ModelsComposition mdl = (ModelsComposition)SparkModelParser.parse(
                SPARK_MDL_PATH,
                SupportedSparkModels.RANDOM_FOREST_REGRESSION
            );

            System.out.println(">>> Random Forest regression model: " + mdl);

            System.out.println(">>> ---------------------------------");
            System.out.println(">>> | Prediction\t| Ground Truth\t|");
            System.out.println(">>> ---------------------------------");

            try (QueryCursor<Cache.Entry<Integer, Object[]>> observations = dataCache.query(new ScanQuery<>())) {
                for (Cache.Entry<Integer, Object[]> observation : observations) {
                    Vector inputs = featureExtractor.apply(observation.getKey(), observation.getValue());
                    double groundTruth = lbExtractor.apply(observation.getKey(), observation.getValue());
                    double prediction = mdl.predict(inputs);

                    System.out.printf(">>> | %.4f\t\t| %.4f\t\t|\n", prediction, groundTruth);
                }
            }

            System.out.println(">>> ---------------------------------");
        }
    }
}