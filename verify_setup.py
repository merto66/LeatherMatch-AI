"""
Setup Verification Script for Phase 2
Checks if all dependencies and files are ready.
"""

import sys
import os


def check_python_version():
    """Check Python version."""
    version = sys.version_info
    print(f"Python version: {version.major}.{version.minor}.{version.micro}")
    if version.major == 3 and version.minor >= 10:
        print("✓ Python version OK")
        return True
    else:
        print("✗ Python 3.10+ required")
        return False


def check_dependencies():
    """Check if required packages are installed."""
    packages = {
        'onnxruntime': 'onnxruntime',
        'numpy': 'numpy',
        'PIL': 'Pillow',
        'sklearn': 'scikit-learn'
    }
    
    all_ok = True
    for module, package in packages.items():
        try:
            __import__(module)
            print(f"✓ {package} installed")
        except ImportError:
            print(f"✗ {package} not installed")
            all_ok = False
    
    return all_ok


def check_paths():
    """Check if required directories and files exist."""
    paths = {
        "Model directory": r"E:\Proje\LeatherMatch-AI\model",
        "Images directory": r"e:\Proje\LeatherMatch-AI\Leather_Images",
        "Data directory": r"e:\Proje\LeatherMatch-AI\data",
        "Model file": r"E:\Proje\LeatherMatch-AI\model\leather_model.onnx"
    }
    
    results = {}
    for name, path in paths.items():
        exists = os.path.exists(path)
        status = "✓" if exists else "✗"
        print(f"{status} {name}: {path}")
        results[name] = exists
    
    return results


def check_pattern_folders():
    """Check pattern folders."""
    images_folder = r"e:\Proje\LeatherMatch-AI\Leather_Images"
    
    if not os.path.exists(images_folder):
        print("✗ Images folder not found")
        return False
    
    folders = [f for f in os.listdir(images_folder) 
               if os.path.isdir(os.path.join(images_folder, f))]
    
    if not folders:
        print("⚠ No pattern folders found (you need to create them)")
        return False
    
    print(f"✓ Found {len(folders)} pattern folders:")
    for folder in sorted(folders)[:5]:
        img_count = len([f for f in os.listdir(os.path.join(images_folder, folder))
                        if f.lower().endswith(('.jpg', '.jpeg', '.png', '.bmp'))])
        print(f"  - {folder}: {img_count} images")
    
    if len(folders) > 5:
        print(f"  ... and {len(folders) - 5} more")
    
    return True


def main():
    print("="*60)
    print("PHASE 2 SETUP VERIFICATION")
    print("LeatherMatch-AI System")
    print("="*60)
    print()
    
    print("1. Checking Python version...")
    python_ok = check_python_version()
    print()
    
    print("2. Checking dependencies...")
    deps_ok = check_dependencies()
    print()
    
    print("3. Checking paths...")
    paths_ok = check_paths()
    print()
    
    print("4. Checking pattern folders...")
    patterns_ok = check_pattern_folders()
    print()
    
    print("="*60)
    print("SUMMARY")
    print("="*60)
    
    if python_ok and deps_ok and paths_ok["Model file"]:
        print("✓ System is ready to run build_database.py")
        
        if not patterns_ok:
            print("\n📝 Next steps:")
            print("1. Create pattern folders in: Leather_Images\\")
            print("2. Add reference images to each pattern folder")
            print("3. Run: python build_database.py")
    else:
        print("✗ System is NOT ready")
        print("\n📝 Please fix the issues above:")
        
        if not python_ok:
            print("- Install Python 3.10+")
        
        if not deps_ok:
            print("- Install dependencies: pip install -r requirements.txt")
        
        if not paths_ok["Model file"]:
            print("- Add ONNX model to: E:\\Proje\\LeatherMatch-AI\\model\\leather_model.onnx")
    
    print("="*60)


if __name__ == "__main__":
    main()
