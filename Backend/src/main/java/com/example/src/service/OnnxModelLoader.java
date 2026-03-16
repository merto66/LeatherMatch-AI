package com.example.src.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

@Service
public class OnnxModelLoader {

    @Value("${onnx.model.path:models/leather_model.onnx}")
    private String modelPath;

    private OrtEnvironment environment;
    private OrtSession session;

    @PostConstruct
    public void init() throws OrtException {
        environment = OrtEnvironment.getEnvironment();
        session = environment.createSession(modelPath, new OrtSession.SessionOptions());
        System.out.println("ONNX Model yüklendi: " + modelPath);
    }

    public float[] predict(float[][][][] inputData) throws OrtException {
        // Input tensor oluştur (batch, channels, height, width)
        OnnxTensor inputTensor = OnnxTensor.createTensor(environment, inputData);
        
        // Model çalıştır
        var inputs = Map.of("input", inputTensor);
        var results = session.run(inputs);
        
        // Sonuçları al
        float[][] output = (float[][]) results.get(0).getValue();
        
        inputTensor.close();
        results.close();
        
        return output[0];
    }

    public OrtSession getSession() {
        return session;
    }

    public OrtEnvironment getEnvironment() {
        return environment;
    }

    @PreDestroy
    public void cleanup() throws OrtException {
        if (session != null) {
            session.close();
        }
        if (environment != null) {
            environment.close();
        }
    }
}
