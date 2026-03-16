"""
Phase 2 - Pattern Embedding Database Builder
Leather Pattern Recognition System

This script scans pattern folders, extracts embeddings using ONNX MobileNetV2,
and creates a JSON database for similarity-based pattern matching.

Author: LeatherMatch-AI Team
Date: 2026-02-16
"""

import os
import json
import numpy as np
from pathlib import Path
from PIL import Image
import onnxruntime as ort
from sklearn.preprocessing import normalize


def load_model(model_path):
    """
    Load ONNX model for feature extraction.
    
    Args:
        model_path (str): Path to the ONNX model file
        
    Returns:
        onnxruntime.InferenceSession: Loaded ONNX model session
        
    Raises:
        FileNotFoundError: If model file doesn't exist
    """
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Model file not found: {model_path}")
    
    print(f"Loading ONNX model from: {model_path}")
    session = ort.InferenceSession(model_path)
    print(f"[OK] Model loaded successfully")
    print(f"  Input name: {session.get_inputs()[0].name}")
    print(f"  Input shape: {session.get_inputs()[0].shape}")
    print(f"  Output name: {session.get_outputs()[0].name}")
    
    return session


def preprocess_image(image_path):
    """
    Preprocess image for MobileNetV2 inference.
    
    Pipeline:
    1. Load image
    2. Convert to RGB
    3. Resize to 224x224
    4. Normalize pixels to [0, 1]
    5. Convert to float32
    6. Transpose to CHW format (PyTorch/ONNX standard)
    7. Add batch dimension
    
    Args:
        image_path (str): Path to the image file
        
    Returns:
        numpy.ndarray: Preprocessed image array with shape (1, 3, 224, 224)
        
    Raises:
        Exception: If image cannot be loaded or processed
    """
    try:
        # Load image
        img = Image.open(image_path)
        
        # Convert to RGB (handle grayscale, RGBA, etc.)
        img = img.convert('RGB')
        
        # Resize to 224x224
        img = img.resize((224, 224), Image.BILINEAR)
        
        # Convert to numpy array
        img_array = np.array(img, dtype=np.float32)
        
        # Normalize to [0, 1]
        img_array = img_array / 255.0
        
        # Transpose from HWC to CHW format: (224, 224, 3) -> (3, 224, 224)
        img_array = np.transpose(img_array, (2, 0, 1))
        
        # Add batch dimension: (3, 224, 224) -> (1, 3, 224, 224)
        img_array = np.expand_dims(img_array, axis=0)
        
        return img_array
        
    except Exception as e:
        raise Exception(f"Failed to preprocess image {image_path}: {str(e)}")


def extract_embedding(session, image_array):
    """
    Extract and normalize embedding from preprocessed image.
    
    Args:
        session (onnxruntime.InferenceSession): ONNX model session
        image_array (numpy.ndarray): Preprocessed image array
        
    Returns:
        list: L2-normalized embedding as a list of floats
    """
    # Get input name from model
    input_name = session.get_inputs()[0].name
    
    # Run inference
    outputs = session.run(None, {input_name: image_array})
    
    # Get feature vector (first output)
    features = outputs[0]
    
    # Flatten if needed
    features = features.flatten().reshape(1, -1)
    
    # Apply L2 normalization
    normalized_features = normalize(features, norm='l2')
    
    # Convert to list for JSON serialization
    embedding = normalized_features[0].tolist()
    
    return embedding


def build_database(images_folder, model_path):
    """
    Build embedding database by scanning pattern folders.
    
    Structure expected:
    images_folder/
        Pattern-1/
            img1.jpg
            img2.jpg
        Pattern-2/
            img1.jpg
            
    Args:
        images_folder (str): Root folder containing pattern subdirectories
        model_path (str): Path to ONNX model
        
    Returns:
        dict: Database dictionary {pattern_code: [embedding1, embedding2, ...]}
    """
    # Load model once
    session = load_model(model_path)
    
    # Initialize database
    database = {}
    
    # Supported image extensions
    image_extensions = {'.jpg', '.jpeg', '.png', '.bmp', '.gif', '.tiff'}
    
    # Check if images folder exists
    if not os.path.exists(images_folder):
        print(f"\n[!] Warning: Images folder not found: {images_folder}")
        print("Creating empty database. Please add pattern folders and images.")
        return database
    
    # Get all pattern folders
    pattern_folders = [f for f in os.listdir(images_folder) 
                      if os.path.isdir(os.path.join(images_folder, f))]
    
    if not pattern_folders:
        print(f"\n[!] Warning: No pattern folders found in: {images_folder}")
        print("Creating empty database. Please add pattern folders and images.")
        return database
    
    print(f"\n{'='*60}")
    print(f"Found {len(pattern_folders)} pattern folders")
    print(f"{'='*60}\n")
    
    # Statistics
    total_images = 0
    total_patterns = 0
    failed_images = []
    
    # Process each pattern folder
    for idx, pattern_code in enumerate(sorted(pattern_folders), 1):
        pattern_path = os.path.join(images_folder, pattern_code)
        
        print(f"[{idx}/{len(pattern_folders)}] Processing pattern: {pattern_code}")
        
        # Get all image files in this pattern folder
        image_files = []
        for file in os.listdir(pattern_path):
            file_path = os.path.join(pattern_path, file)
            if os.path.isfile(file_path):
                _, ext = os.path.splitext(file.lower())
                if ext in image_extensions:
                    image_files.append(file_path)
        
        if not image_files:
            print(f"  [!] No images found, skipping...")
            continue
        
        print(f"  Found {len(image_files)} images")
        
        # Extract embeddings for this pattern
        pattern_embeddings = []
        
        for img_idx, image_path in enumerate(image_files, 1):
            try:
                # Preprocess image
                img_array = preprocess_image(image_path)
                
                # Extract embedding
                embedding = extract_embedding(session, img_array)
                
                pattern_embeddings.append(embedding)
                total_images += 1
                
                # Progress indicator
                if img_idx % 5 == 0 or img_idx == len(image_files):
                    print(f"  -> Processed {img_idx}/{len(image_files)} images", end='\r')
                
            except Exception as e:
                failed_images.append((image_path, str(e)))
                print(f"\n  [X] Failed: {os.path.basename(image_path)} - {str(e)}")
        
        if pattern_embeddings:
            database[pattern_code] = pattern_embeddings
            total_patterns += 1
            print(f"\n  [OK] Extracted {len(pattern_embeddings)} embeddings")
        
        print()
    
    # Print summary
    print(f"{'='*60}")
    print(f"DATABASE BUILD SUMMARY")
    print(f"{'='*60}")
    print(f"Total patterns processed: {total_patterns}")
    print(f"Total embeddings extracted: {total_images}")
    print(f"Failed images: {len(failed_images)}")
    
    if failed_images:
        print(f"\n[!] Failed images:")
        for img_path, error in failed_images[:10]:  # Show first 10
            print(f"  - {os.path.basename(img_path)}: {error}")
        if len(failed_images) > 10:
            print(f"  ... and {len(failed_images) - 10} more")
    
    print(f"{'='*60}\n")
    
    return database


def save_database(database, output_path):
    """
    Save database to JSON file.
    
    Args:
        database (dict): Database dictionary to save
        output_path (str): Path to output JSON file
    """
    # Create output directory if it doesn't exist
    output_dir = os.path.dirname(output_path)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)
        print(f"Created output directory: {output_dir}")
    
    # Save as JSON with formatting
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(database, f, indent=2, ensure_ascii=False)
    
    # Calculate file size
    file_size = os.path.getsize(output_path)
    file_size_mb = file_size / (1024 * 1024)
    
    print(f"[OK] Database saved to: {output_path}")
    print(f"  File size: {file_size_mb:.2f} MB")
    print(f"  Patterns: {len(database)}")
    
    # Calculate total embeddings
    total_embeddings = sum(len(embeddings) for embeddings in database.values())
    print(f"  Total embeddings: {total_embeddings}")
    
    if database:
        # Show embedding dimensions
        first_pattern = next(iter(database.values()))
        if first_pattern:
            embedding_dim = len(first_pattern[0])
            print(f"  Embedding dimension: {embedding_dim}")


def main():
    """
    Main execution function.
    """
    print("\n" + "="*60)
    print("LEATHER PATTERN EMBEDDING DATABASE BUILDER")
    print("Phase 2 - LeatherMatch-AI System")
    print("="*60 + "\n")
    
    # Configuration paths
    MODEL_PATH = r"E:\Proje\LeatherMatch-AI\model\leather_model.onnx"
    IMAGES_FOLDER = r"e:\Proje\LeatherMatch-AI\Leather_Images"
    OUTPUT_PATH = r"e:\Proje\LeatherMatch-AI\data\pattern_database.json"
    
    print("Configuration:")
    print(f"  Model: {MODEL_PATH}")
    print(f"  Images folder: {IMAGES_FOLDER}")
    print(f"  Output: {OUTPUT_PATH}")
    print()
    
    try:
        # Build database
        database = build_database(IMAGES_FOLDER, MODEL_PATH)
        
        # Save database
        save_database(database, OUTPUT_PATH)
        
        print("\n" + "="*60)
        print("[OK] PHASE 2 COMPLETED SUCCESSFULLY")
        print("="*60)
        
        if not database:
            print("\nNext steps:")
            print("1. Create pattern folders in:", IMAGES_FOLDER)
            print("2. Add reference images to each pattern folder")
            print("3. Run this script again to build the database")
        else:
            print("\nNext steps:")
            print("1. Review the generated database file")
            print("2. Proceed to Phase 3: Integrate with Java Spring Boot backend")
            print("3. Implement cosine similarity matching engine")
        
    except FileNotFoundError as e:
        print(f"\n[ERROR]: {e}")
        print("\nPlease ensure:")
        print("1. ONNX model file exists at:", MODEL_PATH)
        print("2. Model is the correct MobileNetV2 feature extractor")
        
    except Exception as e:
        print(f"\n[ERROR]: {e}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
