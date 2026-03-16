package com.example.src.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.springframework.stereotype.Service;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * EmbeddingService handles ONNX model inference to extract embedding vectors.
 * Uses FloatBuffer for efficient tensor creation and memory management.
 */
@Service
public class EmbeddingService {
    
    private final OnnxModelLoader modelLoader;
    private final String inputName;
    
    // Input tensor shape: [batch_size, channels, height, width]
    private static final long[] INPUT_SHAPE = new long[]{1, 3, 224, 224};
    
    public EmbeddingService(OnnxModelLoader modelLoader) {
        this.modelLoader = modelLoader;
        OrtSession session = modelLoader.getSession();
        this.inputName = session.getInputNames().iterator().next();
    }
    
    /**
     * Extracts embedding vector from preprocessed image data.
     * Uses FloatBuffer method for efficient tensor creation.
     * 
     * @param flatArray Preprocessed image as flat float array (150,528 elements)
     * @return Embedding vector as float array
     * @throws OrtException if inference fails
     */
    public float[] extractEmbedding(float[] flatArray) throws OrtException {
        OrtEnvironment environment = modelLoader.getEnvironment();
        OrtSession session = modelLoader.getSession();
        
        // Validate input size
        int expectedSize = 1 * 3 * 224 * 224; // 150,528
        if (flatArray.length != expectedSize) {
            throw new IllegalArgumentException(
                String.format("Invalid input size. Expected %d, got %d", 
                    expectedSize, flatArray.length)
            );
        }
        
        OnnxTensor inputTensor = null;
        OrtSession.Result results = null;
        
        try {
            // Create FloatBuffer from flat array (efficient method)
            FloatBuffer buffer = FloatBuffer.wrap(flatArray);
            
            // Create ONNX tensor with explicit shape
            inputTensor = OnnxTensor.createTensor(environment, buffer, INPUT_SHAPE);

            // Run inference
            results = session.run(Collections.singletonMap(inputName, inputTensor));
            
            // Extract output tensor
            OnnxTensor outputTensor = (OnnxTensor) results.get(0);
            
            // Convert to float array
            float[][] output2D = (float[][]) outputTensor.getValue();
            float[] embedding = output2D[0]; // Get first batch
            
            return embedding;
            
        } finally {
            // Cleanup resources to prevent memory leaks
            if (inputTensor != null) {
                inputTensor.close();
            }
            if (results != null) {
                results.close();
            }
        }
    }
    
    /**
     * Extracts embedding and returns detailed information.
     * Useful for debugging and validation.
     * 
     * @param flatArray Preprocessed image as flat float array
     * @return EmbeddingResult containing the embedding and metadata
     * @throws OrtException if inference fails
     */
    public EmbeddingResult extractEmbeddingWithMetadata(float[] flatArray) throws OrtException {
        long startTime = System.currentTimeMillis();
        
        float[] embedding = extractEmbedding(flatArray);
        
        long endTime = System.currentTimeMillis();
        long inferenceTimeMs = endTime - startTime;
        
        return new EmbeddingResult(embedding, inferenceTimeMs);
    }
    
    /**
     * Simple data class to hold embedding results with metadata.
     */
    public static class EmbeddingResult {
        private final float[] embedding;
        private final long inferenceTimeMs;
        
        public EmbeddingResult(float[] embedding, long inferenceTimeMs) {
            this.embedding = embedding;
            this.inferenceTimeMs = inferenceTimeMs;
        }
        
        public float[] getEmbedding() {
            return embedding;
        }
        
        public long getInferenceTimeMs() {
            return inferenceTimeMs;
        }
        
        public int getEmbeddingLength() {
            return embedding.length;
        }
    }
}
