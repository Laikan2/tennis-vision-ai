"""Bundle runnable source, Android APK and model weights for another PC."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "Tennis-Vision-AI"
mobile_version = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"',
    (ROOT / 'mobile/android/app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
ANDROID_APK_NAME = f"Tennis-Vision-Camera-Android-v{mobile_version}.apk"
SKIP_DIRS = {
    "__pycache__", ".pytest_cache", "node_modules", ".next", ".gradle",
    ".kotlin", ".idea", "build", ".git", ".pnpm-store", ".turbo", ".cache", "out", "releases",
}
SKIP_FILES = {"local.properties", "auto.key", "auto.crt", ".DS_Store"}
ROOT_FILES = (
    ".env.example", ".gitignore", "ABRIR.bat", "INSTALAR.bat",
    "LIBERAR-CAMERA-CELULAR.bat", "LEIA-ME-PRIMEIRO.txt", "README.md",
)


def package_files() -> list[Path]:
    files = {ROOT / name for name in ROOT_FILES}
    for directory in ("backend", "frontend", "docs", "scripts", "mobile", "tools", "desktop"):
        for path in (ROOT / directory).rglob("*"):
            relative = path.relative_to(ROOT)
            if not path.is_file() or path.is_symlink():
                continue
            if any(part in SKIP_DIRS for part in relative.parts) and not (
                directory == "mobile" and path.parent == ROOT / "mobile" / "releases"):
                continue
            if any(part.startswith(".next") for part in relative.parts):
                continue
            if path.name in SKIP_FILES or path.suffix in {".log", ".pyc", ".tsbuildinfo"}:
                continue
            if directory == "mobile" and path.suffix == ".apk" and path.name != ANDROID_APK_NAME:
                continue
            if directory == "desktop" and path.suffix == ".tgz":
                continue
            if directory == "mobile" and ".apk.sha256" in path.name and path.name != ANDROID_APK_NAME + ".sha256":
                continue
            files.add(path)

    # Carry trained weights, without videos, crops, credentials or camera profiles.
    files.update((ROOT / "data" / "models").glob("*.pt"))
    active_path = ROOT / "data" / "learning" / "active.json"
    if active_path.is_file():
        active = json.loads(active_path.read_text(encoding="utf-8"))
        model = (active_path.parent / active["file"]).resolve()
        model_root = (active_path.parent / "models").resolve()
        if not model.is_relative_to(model_root) or model.suffix != ".npz":
            raise ValueError("Active model must be a local .npz inside learning/models")
        if not model.is_file():
            raise FileNotFoundError(model)
        files.update((active_path, model))
    return sorted(files)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    destination = args.output.resolve()
    if destination.exists():
        raise FileExistsError(f"Choose a new archive name: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    files = package_files()
    with ZipFile(destination, "x", ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            archive.write(path, f"{PREFIX}/{path.relative_to(ROOT).as_posix()}")
    with ZipFile(destination) as archive:
        invalid = archive.testzip()
        if invalid:
            raise ValueError(f"Archive integrity error: {invalid}")
        names = archive.namelist()
        assert any(name.endswith(ANDROID_APK_NAME) for name in names)
        assert not any(Path(name).name in {".env", "auto.key", "local.properties"} for name in names)
        assert not any(part.startswith(".next") for name in names for part in Path(name).parts)
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".zip.sha256").write_text(
        f"{checksum}  {destination.name}\n", encoding="ascii"
    )
    print(json.dumps({"path": str(destination), "files": len(files),
                      "bytes": destination.stat().st_size, "sha256": checksum}))


if __name__ == "__main__":
    main()
