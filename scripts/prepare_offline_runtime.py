from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
from typing import Iterable, List


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare an offline runtime directory from local project assets.")
    parser.add_argument("--source", default=".", help="Project root containing python_detector and third_party")
    parser.add_argument("--target", required=True, help="Target runtime directory")
    parser.add_argument("--clean", action="store_true", help="Remove target runtime contents before copying")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_root = Path(args.source).resolve()
    target_root = Path(args.target).resolve()
    if args.clean and target_root.exists():
        shutil.rmtree(target_root)
    target_root.mkdir(parents=True, exist_ok=True)

    copied: List[str] = []
    copied.extend(copy_tree(source_root / "python_detector", target_root / "python_detector"))
    copied.extend(copy_tree(source_root / "third_party", target_root / "third_party"))

    payload = {
        "source": str(source_root),
        "target": str(target_root),
        "copiedFiles": len(copied),
        "paths": copied,
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def copy_tree(source: Path, target: Path) -> List[str]:
    if not source.exists():
        return []
    copied: List[str] = []
    for path in iter_files(source):
        relative = path.relative_to(source)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        copied.append(str(destination))
    return copied


def iter_files(root: Path) -> Iterable[Path]:
    for path in root.rglob("*"):
        if path.is_file():
            yield path


if __name__ == "__main__":
    raise SystemExit(main())
