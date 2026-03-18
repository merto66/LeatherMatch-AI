package com.example.src.preprocessing;

import com.example.src.util.PathValidator;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * ImagePreprocessor handles image preprocessing for MobileNetV2 model.
 * Converts images to the required format: 224x224 RGB, normalized to 0-1 range.
 * Returns a flat float array in NCHW format (batch, channel, height, width).
 */
@Component
public class ImagePreprocessor {
    
    private static final int TARGET_WIDTH = 224;
    private static final int TARGET_HEIGHT = 224;
    private static final int CHANNELS = 3; // RGB
    
    private final PathValidator pathValidator;
    
    public ImagePreprocessor(PathValidator pathValidator) {
        this.pathValidator = pathValidator;
    }
    
    /**
     * Main preprocessing pipeline: loads, resizes, converts to RGB, normalizes, and flattens.
     *
     * @param imagePath Path to the input image
     * @return Flat float array of size 150,528 (1 × 3 × 224 × 224) in NCHW format
     * @throws IOException if image cannot be loaded
     */
    public float[] preprocessImage(String imagePath) throws IOException {
        System.out.println("Loading image from disk: " + imagePath);

        BufferedImage originalImage = loadImage(imagePath);
        return preprocessBuffered(originalImage);
    }

    /**
     * Overload used by the match endpoint to avoid writing the upload to disk.
     * Reads directly from the uploaded stream but performs the same resize and
     * normalization steps, so embeddings remain consistent.
     */
    public float[] preprocessImage(InputStream inputStream) throws IOException {
        BufferedImage originalImage = loadImage(inputStream);
        return preprocessBuffered(originalImage);
    }

    private float[] preprocessBuffered(BufferedImage originalImage) {
        // Step 2: Resize to 224x224
        BufferedImage resizedImage = resizeImage(originalImage, TARGET_WIDTH, TARGET_HEIGHT);

        // Step 3: Convert to RGB format
        BufferedImage rgbImage = convertToRGB(resizedImage);

        // Step 4: Convert to flat float array and normalize
        float[] flatArray = imageToFlatArray(rgbImage);

        System.out.println("Image preprocessed successfully. Array size: " + flatArray.length);

        return flatArray;
    }
    
    /**
     * Loads an image from the specified path.
     *
     * @param path Path to the image file
     * @return BufferedImage loaded from disk
     * @throws IOException if the image cannot be read
     */
    private BufferedImage loadImage(String path) throws IOException {
        File imageFile = new File(path);
        if (!imageFile.exists()) {
            throw new IOException("Image file not found: " + path);
        }

        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Failed to read image file: " + path);
        }

        return image;
    }

    /**
     * Loads an image from an InputStream.
     *
     * @param inputStream input stream with encoded image bytes
     * @return BufferedImage loaded from the stream
     * @throws IOException if the image cannot be read
     */
    private BufferedImage loadImage(InputStream inputStream) throws IOException {
        BufferedImage image = ImageIO.read(inputStream);
        if (image == null) {
            throw new IOException("Failed to read image from stream");
        }
        return image;
    }
    
    /**
     * Resizes the image to the specified dimensions using high-quality scaling.
     * 
     * @param img Original image
     * @param width Target width
     * @param height Target height
     * @return Resized BufferedImage
     */
    private BufferedImage resizeImage(BufferedImage img, int width, int height) {
        Image scaledImage = img.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        
        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        resizedImage.getGraphics().drawImage(scaledImage, 0, 0, null);
        
        return resizedImage;
    }
    
    /**
     * Converts the image to RGB format if it's not already.
     * 
     * @param img Input image
     * @return BufferedImage in TYPE_INT_RGB format
     */
    private BufferedImage convertToRGB(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        
        BufferedImage rgbImage = new BufferedImage(
            img.getWidth(), 
            img.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );
        
        rgbImage.getGraphics().drawImage(img, 0, 0, null);
        
        return rgbImage;
    }
    
    /**
     * Converts BufferedImage to a flat float array in NCHW format.
     * NCHW order: [batch][channel][height][width]
     * - All Red channel pixels (224×224 = 50,176)
     * - All Green channel pixels (50,176)
     * - All Blue channel pixels (50,176)
     * Total: 150,528 elements
     * 
     * Pixels are normalized to 0-1 range by dividing by 255.0.
     * 
     * @param img Input image (must be 224x224 RGB)
     * @return Flat float array of size 150,528
     */
    private float[] imageToFlatArray(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        
        // Total elements: 1 batch × 3 channels × 224 height × 224 width = 150,528
        float[] flatArray = new float[1 * CHANNELS * height * width];
        
        int pixelsPerChannel = height * width; // 224 × 224 = 50,176
        
        // Extract pixel values in NCHW format
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int rgb = img.getRGB(w, h);
                
                // Extract RGB components (0-255)
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Normalize to 0-1 range and place in NCHW order
                int pixelIndex = h * width + w;
                
                // Channel 0: Red
                flatArray[pixelIndex] = red / 255.0f;
                
                // Channel 1: Green
                flatArray[pixelsPerChannel + pixelIndex] = green / 255.0f;
                
                // Channel 2: Blue
                flatArray[2 * pixelsPerChannel + pixelIndex] = blue / 255.0f;
            }
        }
        
        return flatArray;
    }
}
