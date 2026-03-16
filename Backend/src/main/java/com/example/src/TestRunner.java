package com.example.src;

import com.example.src.preprocessing.ImagePreprocessor;
import com.example.src.service.EmbeddingService;
import com.example.src.service.OnnxModelLoader;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * TestRunner - Standalone application to test Phase 1 implementation.
 * 
 * This class demonstrates the complete pipeline:
 * 1. Load ONNX model (MobileNetV2)
 * 2. Preprocess test image
 * 3. Extract embedding vector
 * 
 * NOTE: This is NOT a Spring application - manually instantiates all components.
 */
public class TestRunner {
    
    // Configuration
    private static final String MODEL_PATH = "E:\\Proje\\LeatherMatch-AI\\model\\leather_model.onnx";
    private static final String TEST_IMAGE_PATH = "E:\\Proje\\LeatherMatch-AI\\test_images\\test.jpg";
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Phase 1: Model Engine Setup - Test Run");
        System.out.println("========================================\n");
        
        OnnxModelLoader modelLoader = null;
        
        try {
            // Step 1: Load ONNX Model
            System.out.println("Step 1: Loading ONNX model");
            System.out.println("Model path: " + MODEL_PATH);
            
            modelLoader = createModelLoader(MODEL_PATH);
            System.out.println("✓ Model loaded successfully\n");
            
            // Step 2: Create ImagePreprocessor
            System.out.println("Step 2: Initializing ImagePreprocessor");
            ImagePreprocessor imagePreprocessor = new ImagePreprocessor();
            System.out.println("✓ ImagePreprocessor initialized\n");
            
            // Step 3: Create EmbeddingService
            System.out.println("Step 3: Initializing EmbeddingService");
            EmbeddingService embeddingService = new EmbeddingService(modelLoader);
            System.out.println("✓ EmbeddingService initialized\n");
            
            // Step 4: Preprocess test image
            System.out.println("Step 4: Preprocessing test image");
            System.out.println("Image path: " + TEST_IMAGE_PATH);
            
            float[] preprocessedImage = imagePreprocessor.preprocessImage(TEST_IMAGE_PATH);
            System.out.println("✓ Image preprocessed successfully");
            System.out.println("  - Preprocessed array size: " + preprocessedImage.length + " elements");
            System.out.println("  - Expected size: " + (1 * 3 * 224 * 224) + " elements (1×3×224×224)\n");
            
            // Step 5: Extract embedding
            System.out.println("Step 5: Extracting embedding vector");
            
            EmbeddingService.EmbeddingResult result = 
                embeddingService.extractEmbeddingWithMetadata(preprocessedImage);
            
            System.out.println("✓ Embedding extracted successfully");
            System.out.println("  - Embedding vector length: " + result.getEmbeddingLength());
            System.out.println("  - Inference time: " + result.getInferenceTimeMs() + " ms\n");
            
            // Step 6: Display sample embedding values
            System.out.println("Step 6: Sample embedding values (first 10 elements)");
            float[] embedding = result.getEmbedding();
            for (int i = 0; i < Math.min(10, embedding.length); i++) {
                System.out.printf("  embedding[%d] = %.6f\n", i, embedding[i]);
            }
            
            // Success summary
            System.out.println("\n========================================");
            System.out.println("✓ Phase 1 Test COMPLETED SUCCESSFULLY");
            System.out.println("========================================");
            System.out.println("\nSummary:");
            System.out.println("- Model: MobileNetV2-7 (ONNX)");
            System.out.println("- Input image: " + TEST_IMAGE_PATH);
            System.out.println("- Preprocessed size: " + preprocessedImage.length + " floats");
            System.out.println("- Embedding dimension: " + result.getEmbeddingLength());
            System.out.println("- Total inference time: " + result.getInferenceTimeMs() + " ms");
            
        } catch (Exception e) {
            System.err.println("\n✗ ERROR occurred during execution:");
            System.err.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println("\nStack trace:");
            e.printStackTrace();
            System.exit(1);
            
        } finally {
            // Step 7: Cleanup resources
            if (modelLoader != null) {
                try {
                    System.out.println("\nCleaning up resources...");
                    cleanupModelLoader(modelLoader);
                    System.out.println("✓ Resources cleaned up successfully");
                } catch (Exception e) {
                    System.err.println("Warning: Error during cleanup: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Creates and initializes an OnnxModelLoader instance without Spring.
     * Manually sets up the ONNX environment and session.
     * 
     * @param modelPath Path to the ONNX model file
     * @return Initialized OnnxModelLoader instance
     * @throws Exception if model loading fails
     */
    private static OnnxModelLoader createModelLoader(String modelPath) throws Exception {
        OnnxModelLoader loader = new OnnxModelLoader();
        
        // Use reflection to set the model path (since it's a private field)
        java.lang.reflect.Field pathField = OnnxModelLoader.class.getDeclaredField("modelPath");
        pathField.setAccessible(true);
        pathField.set(loader, modelPath);
        
        // Manually call the init method (normally called by Spring @PostConstruct)
        java.lang.reflect.Method initMethod = OnnxModelLoader.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(loader);
        
        return loader;
    }
    
    /**
     * Manually cleans up OnnxModelLoader resources.
     * Calls the cleanup method that's normally triggered by Spring @PreDestroy.
     * 
     * @param loader The OnnxModelLoader instance to clean up
     * @throws Exception if cleanup fails
     */
    private static void cleanupModelLoader(OnnxModelLoader loader) throws Exception {
        // Manually call the cleanup method (normally called by Spring @PreDestroy)
        java.lang.reflect.Method cleanupMethod = OnnxModelLoader.class.getDeclaredMethod("cleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(loader);
    }
}
