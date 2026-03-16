"""
Test Script - Pattern Similarity Matcher
Tests the embedding database with new images.

Usage:
    python test_similarity.py <image_path>
    python test_similarity.py test_images/sample.jpg
"""

import os
import sys
import json
import numpy as np
from PIL import Image
import onnxruntime as ort
from sklearn.preprocessing import normalize


def load_model(model_path):
    """Load ONNX model."""
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Model file not found: {model_path}")
    session = ort.InferenceSession(model_path)
    return session


def preprocess_image(image_path):
    """Preprocess image for inference."""
    img = Image.open(image_path)
    img = img.convert('RGB')
    img = img.resize((224, 224), Image.BILINEAR)
    img_array = np.array(img, dtype=np.float32)
    img_array = img_array / 255.0
    img_array = np.transpose(img_array, (2, 0, 1))  # HWC to CHW
    img_array = np.expand_dims(img_array, axis=0)   # Add batch
    return img_array


def extract_embedding(session, image_array):
    """Extract embedding from image."""
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: image_array})
    features = outputs[0].flatten().reshape(1, -1)
    normalized_features = normalize(features, norm='l2')
    return normalized_features[0]


def cosine_similarity(vec1, vec2):
    """Calculate cosine similarity between two vectors."""
    return np.dot(vec1, vec2)


def find_best_match(query_embedding, database):
    """
    Find best matching pattern.
    
    Returns:
        pattern_code: Best matching pattern
        max_similarity: Highest similarity score
        all_scores: Dictionary of all pattern scores
    """
    pattern_scores = {}
    
    for pattern_code, embeddings in database.items():
        # Calculate similarity with all embeddings of this pattern
        similarities = [cosine_similarity(query_embedding, emb) for emb in embeddings]
        # Use max similarity (best match from this pattern)
        max_sim = max(similarities)
        avg_sim = np.mean(similarities)
        
        pattern_scores[pattern_code] = {
            'max': max_sim,
            'avg': avg_sim,
            'min': min(similarities),
            'count': len(similarities)
        }
    
    # Find pattern with highest max similarity
    best_pattern = max(pattern_scores.keys(), key=lambda k: pattern_scores[k]['max'])
    best_score = pattern_scores[best_pattern]['max']
    
    return best_pattern, best_score, pattern_scores


def test_image(image_path, model_path, database_path, threshold=0.70):
    """
    Test a single image against the database.
    
    Args:
        image_path: Path to test image
        model_path: Path to ONNX model
        database_path: Path to database JSON
        threshold: Similarity threshold (default 0.70)
    """
    print("\n" + "="*70)
    print("LEATHER PATTERN SIMILARITY TEST")
    print("="*70)
    
    # Load resources
    print(f"\n[1/4] Loading model...")
    session = load_model(model_path)
    print(f"      [OK] Model loaded")
    
    print(f"\n[2/4] Loading database...")
    with open(database_path, 'r', encoding='utf-8') as f:
        db_data = json.load(f)
    
    # Convert database embeddings to numpy arrays
    database = {}
    for pattern, embeddings in db_data.items():
        database[pattern] = [np.array(emb, dtype=np.float32) for emb in embeddings]
    
    print(f"      [OK] Database loaded")
    print(f"      Patterns: {len(database)}")
    total_refs = sum(len(embs) for embs in database.values())
    print(f"      Total reference embeddings: {total_refs}")
    
    # Process test image
    print(f"\n[3/4] Processing test image: {os.path.basename(image_path)}")
    img_array = preprocess_image(image_path)
    query_embedding = extract_embedding(session, img_array)
    print(f"      [OK] Embedding extracted (dim: {len(query_embedding)})")
    
    # Find best match
    print(f"\n[4/4] Finding best match...")
    best_pattern, best_score, all_scores = find_best_match(query_embedding, database)
    
    # Display results
    print("\n" + "="*70)
    print("RESULTS")
    print("="*70)
    
    # Sort patterns by max similarity
    sorted_patterns = sorted(all_scores.items(), 
                            key=lambda x: x[1]['max'], 
                            reverse=True)
    
    print(f"\nBest Match: {best_pattern}")
    print(f"Similarity Score: {best_score:.4f}")
    print(f"Threshold: {threshold}")
    
    if best_score >= threshold:
        print(f"\nDecision: [MATCH FOUND] -> {best_pattern}")
        confidence = "HIGH" if best_score >= 0.80 else "MEDIUM"
        print(f"Confidence: {confidence}")
    else:
        print(f"\nDecision: [UNCERTAIN - Manual Check Required]")
        print(f"Reason: Score {best_score:.4f} < Threshold {threshold}")
    
    print("\n" + "-"*70)
    print("All Pattern Scores:")
    print("-"*70)
    print(f"{'Pattern':<15} {'Max Sim':<10} {'Avg Sim':<10} {'Min Sim':<10} {'Refs':<8}")
    print("-"*70)
    
    for pattern, scores in sorted_patterns:
        marker = " <-- BEST" if pattern == best_pattern else ""
        print(f"{pattern:<15} {scores['max']:<10.4f} {scores['avg']:<10.4f} "
              f"{scores['min']:<10.4f} {scores['count']:<8}{marker}")
    
    print("="*70)
    
    return best_pattern, best_score


def batch_test(test_folder, model_path, database_path, threshold=0.70):
    """Test all images in a folder."""
    print("\n" + "="*70)
    print("BATCH TEST MODE")
    print("="*70)
    
    # Find all images
    image_extensions = {'.jpg', '.jpeg', '.png', '.bmp'}
    test_images = []
    
    for file in os.listdir(test_folder):
        ext = os.path.splitext(file.lower())[1]
        if ext in image_extensions:
            test_images.append(os.path.join(test_folder, file))
    
    if not test_images:
        print(f"No images found in: {test_folder}")
        return
    
    print(f"\nFound {len(test_images)} test images")
    print(f"Threshold: {threshold}")
    
    # Load model and database once
    session = load_model(model_path)
    with open(database_path, 'r', encoding='utf-8') as f:
        db_data = json.load(f)
    
    database = {}
    for pattern, embeddings in db_data.items():
        database[pattern] = [np.array(emb, dtype=np.float32) for emb in embeddings]
    
    # Test each image
    results = []
    correct = 0
    uncertain = 0
    
    print("\n" + "-"*70)
    print(f"{'Image':<30} {'Predicted':<15} {'Score':<10} {'Status':<15}")
    print("-"*70)
    
    for img_path in test_images:
        img_name = os.path.basename(img_path)
        
        # Extract embedding
        img_array = preprocess_image(img_path)
        query_embedding = extract_embedding(session, img_array)
        
        # Find match
        best_pattern, best_score, _ = find_best_match(query_embedding, database)
        
        # Determine status
        if best_score >= threshold:
            status = "MATCH"
            # Try to extract true label from filename (e.g., "L-1_test.jpg" -> "L-1")
            true_label = None
            for pattern in database.keys():
                if pattern in img_name:
                    true_label = pattern
                    break
            
            if true_label and true_label == best_pattern:
                correct += 1
                status = "CORRECT"
            elif true_label:
                status = "WRONG"
        else:
            status = "UNCERTAIN"
            uncertain += 1
        
        print(f"{img_name:<30} {best_pattern:<15} {best_score:<10.4f} {status:<15}")
        results.append({
            'image': img_name,
            'predicted': best_pattern,
            'score': best_score,
            'status': status
        })
    
    # Summary
    print("-"*70)
    print(f"\nSummary:")
    print(f"  Total tests: {len(test_images)}")
    print(f"  Matched (score >= {threshold}): {len(test_images) - uncertain}")
    print(f"  Uncertain (score < {threshold}): {uncertain}")
    if correct > 0:
        print(f"  Correct predictions: {correct}")
        accuracy = (correct / len(test_images)) * 100
        print(f"  Accuracy: {accuracy:.1f}%")
    
    print("="*70)


def main():
    """Main execution."""
    # Configuration
    MODEL_PATH = r"E:\Proje\LeatherMatch-AI\model\leather_model.onnx"
    DATABASE_PATH = r"e:\Proje\LeatherMatch-AI\data\pattern_database.json"
    THRESHOLD = 0.70  # Adjust based on testing (0.60 - 0.75 recommended)
    
    # Check arguments
    if len(sys.argv) < 2:
        print("\nUsage:")
        print("  Single image test:")
        print("    python test_similarity.py <image_path>")
        print("    python test_similarity.py test_images/sample.jpg")
        print("\n  Batch test (folder):")
        print("    python test_similarity.py <folder_path> --batch")
        print("    python test_similarity.py test_images/ --batch")
        print("\n  Custom threshold:")
        print("    python test_similarity.py <image_path> --threshold 0.75")
        sys.exit(1)
    
    # Parse arguments
    input_path = sys.argv[1]
    batch_mode = '--batch' in sys.argv
    
    # Check for custom threshold
    if '--threshold' in sys.argv:
        idx = sys.argv.index('--threshold')
        if idx + 1 < len(sys.argv):
            THRESHOLD = float(sys.argv[idx + 1])
    
    # Run test
    try:
        if batch_mode or os.path.isdir(input_path):
            batch_test(input_path, MODEL_PATH, DATABASE_PATH, THRESHOLD)
        else:
            test_image(input_path, MODEL_PATH, DATABASE_PATH, THRESHOLD)
    
    except FileNotFoundError as e:
        print(f"\n[ERROR]: {e}")
    except Exception as e:
        print(f"\n[ERROR]: {e}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
