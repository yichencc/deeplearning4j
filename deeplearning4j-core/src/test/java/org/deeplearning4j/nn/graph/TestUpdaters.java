package org.deeplearning4j.nn.graph;

import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.updater.BaseUpdater;
import org.deeplearning4j.nn.updater.NesterovsUpdater;
import org.deeplearning4j.nn.updater.graph.ComputationGraphUpdater;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Created by Alex on 05/08/2016.
 */
public class TestUpdaters {

    @Test
    public void testUpdaters() throws Exception {

        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(Updater.NESTEROVS).momentum(0.9)
                .graphBuilder()
                .addInputs("input") // 40x40x1
                .addLayer("l0_cnn", new ConvolutionLayer.Builder(new int[]{3, 3}, new int[]{1, 1}, new int[]{1, 1}).nIn(1).nOut(100).build(), "input") // 40x40x100
                .addLayer("l1_max", new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{3, 3}, new int[]{2,2}, new int[]{1, 1}).build(), "l0_cnn") // 20x20x100
                .addLayer("l2_max", new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{3, 3}, new int[]{2,2}, new int[]{1, 1}).build(), "l1_max") // 10x10x100
                .addLayer("l3_cnn", new ConvolutionLayer.Builder(new int[]{3, 3}, new int[]{1, 1}, new int[]{1, 1}).nIn(1).nOut(832).build(), "l2_max") // 10x10x832
                .addLayer("l4_max", new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{3, 3}, new int[]{2,2}, new int[]{1, 1}).build(), "l3_cnn") // 5x5x832
                .addLayer("l5_fc", new DenseLayer.Builder().nOut(1024).build(), "l4_max") // output: 1x1x1024
                .addLayer("l6_out", new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .nIn(1024).nOut(10).activation("softmax").build(), "l5_fc")
                .setOutputs("l6_out")
                .backprop(true).pretrain(false)
                .setInputTypes(InputType.convolutional(40,40,1))
                .build();



        ComputationGraph g = new ComputationGraph(conf);
        g.init();
        g.initGradientsView();

        ComputationGraphUpdater updater = g.getUpdater();

        //First: get the updaters array
        Field layerUpdatersField = updater.getClass().getDeclaredField("layerUpdaters");
        layerUpdatersField.setAccessible(true);
        org.deeplearning4j.nn.api.Updater[] layerUpdaters = (org.deeplearning4j.nn.api.Updater[])layerUpdatersField.get(updater);

        //And get the map between names and updater indexes
        Field layerUpdatersMapField = updater.getClass().getDeclaredField("layerUpdatersMap");
        layerUpdatersMapField.setAccessible(true);
        Map<String,Integer> layerUpdatersMap = (Map<String,Integer>)layerUpdatersMapField.get(updater);


        //Go through each layer; check that the updater state size matches the parameters size
        org.deeplearning4j.nn.api.Layer[] layers = g.getLayers();
        for(org.deeplearning4j.nn.api.Layer l : layers){
            String layerName = l.conf().getLayer().getLayerName();
            int nParams = l.numParams();
            Map<String,INDArray> paramTable = l.paramTable();


            Map<String,Integer> parameterSizeCounts = new LinkedHashMap<>();
            for(Map.Entry<String,INDArray> e : paramTable.entrySet()){
                parameterSizeCounts.put(e.getKey(), e.getValue().length());
            }

            int updaterIdx = layerUpdatersMap.get(layerName);
            org.deeplearning4j.nn.api.Updater u = layerUpdaters[updaterIdx];

            NesterovsUpdater nu = (NesterovsUpdater)u;

            Field updaterForVariableField = BaseUpdater.class.getDeclaredField("updaterForVariable");
            updaterForVariableField.setAccessible(true);
            Map<String,GradientUpdater> updaterForVariable = (Map<String,GradientUpdater>)updaterForVariableField.get(nu);
            Map<String,Integer> updaterStateSizeCounts = new HashMap<>();
            for(Map.Entry<String,GradientUpdater> entry : updaterForVariable.entrySet()){
                GradientUpdater gu = entry.getValue();
                Nesterovs nesterovs = (Nesterovs)gu;
                INDArray v = nesterovs.getV();
                int length = (v == null ? -1 : v.length());
                updaterStateSizeCounts.put(entry.getKey(), length);
            }

            //Check subsampling layers:
            if(l.numParams() == 0){
                assertEquals(0, updaterForVariable.size());
            }

            System.out.println(layerName + "\t" + nParams + "\t" + parameterSizeCounts + "\t Updater size: " + updaterStateSizeCounts);

            //Now, with nesterov updater: 1 history value per parameter
            for(String s : parameterSizeCounts.keySet()){
                int paramSize = parameterSizeCounts.get(s);
                int updaterSize = updaterStateSizeCounts.get(s);

                assertEquals(layerName+"/"+s, paramSize, updaterSize);
            }

        }

        INDArray in = Nd4j.create(2,40*40*1);
        INDArray l = Nd4j.create(2,10);

        DataSet ds = new DataSet(in,l);

        g.fit(ds);
    }

}
