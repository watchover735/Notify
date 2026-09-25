import os
import sys
import time
from huggingface_hub import HfApi

TOKEN = os.getenv("HF_TOKEN", "")
REPO_NAME = "notify-ytdlp-backend"
USERNAME = "notifyyyy"
REPO_ID = f"{USERNAME}/{REPO_NAME}"

def deploy():
    print(f"Connecting to Hugging Face as {USERNAME}...")
    api = HfApi(token=TOKEN)

    # 1. Create space if not exists
    try:
        print(f"Creating Space '{REPO_ID}' (Docker SDK, Public)...")
        space_url = api.create_repo(
            repo_id=REPO_ID,
            repo_type="space",
            space_sdk="docker",
            private=False,
            exist_ok=True
        )
        print(f"Space created or already exists: {space_url}")
    except Exception as e:
        print(f"Error creating space: {e}")
        return False

    # 2. Upload folder
    script_dir = os.path.dirname(os.path.abspath(__file__))
    print(f"Uploading files from {script_dir} to {REPO_ID}...")
    try:
        api.upload_folder(
            folder_path=script_dir,
            repo_id=REPO_ID,
            repo_type="space",
            ignore_patterns=["deploy_hf.py", "__pycache__/*", "*.pyc"]
        )
        print("Upload completed successfully!")
    except Exception as e:
        print(f"Error uploading files: {e}")
        return False

    # 3. Check runtime status
    print("Waiting for Space build / startup...")
    for _ in range(30):
        try:
            runtime = api.get_space_runtime(repo_id=REPO_ID)
            stage = runtime.stage
            print(f"Current Space stage: {stage}")
            if stage in ["RUNNING", "RUNNING_BUILDING"]:
                print(f"Space is LIVE! URL: https://{USERNAME.lower()}-{REPO_NAME}.hf.space")
                return True
            elif stage in ["BUILD_ERROR", "RUNTIME_ERROR"]:
                print(f"Error in space runtime: {stage}")
                return False
        except Exception as e:
            print(f"Status check note: {e}")
        time.sleep(5)

    print("Space deployment in progress. Please check dashboard in a moment.")
    return True

if __name__ == "__main__":
    success = deploy()
    sys.exit(0 if success else 1)
