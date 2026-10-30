"""Create an empty, private real-data workspace; never copy production records."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .schema import Gold, Manifest, Query, RankedResult, Record


def make_private_directory(directory: Path) -> None:
    """Create new intermediate directories with 0700 as well as the leaf."""
    missing = []
    parent = directory.absolute().parent
    while not parent.exists():
        missing.append(parent)
        parent = parent.parent
    for ancestor in reversed(missing):
        ancestor.mkdir(mode=0o700, exist_ok=True)
    directory.mkdir(mode=0o700, exist_ok=False)


def create_template(directory: Path) -> None:
    # Never overwrite an existing dataset or write fake examples as real labels.
    make_private_directory(directory)
    manifest = {"schema_version": 1, "dataset_id": "real-history-pilot-v1", "kind": "real_labeled",
                "description": "待采集和标注的真实生活记录；当前为空，不可用于任何检索指标结论。",
                "annotation_protocol": "请填写参与者、采集范围、相关性定义、复核方法、切分策略和冻结日期；不记录身份信息。"}
    files = {"manifest.json": json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
             "records.jsonl": "", "queries.jsonl": "", "gold.jsonl": "",
             ".gitignore": "*\n",
             "README.txt": "这是空的私有真实集模板。请先阅读仓库 server/evals/visual_retrieval/templates/README.md。\n"
                            "不要把真实照片、原始语音、令牌或可识别用户的信息提交到代码仓库。\n"
                            "空模板会被评测器拒绝。采集标注完成后运行 --verify-media，再冻结 test 集。\n"}
    for name, content in files.items():
        path = directory / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o600)
    (directory / "images").mkdir(mode=0o700)
    schema = {model.__name__: model.model_json_schema() for model in (Manifest, Record, Query, Gold, RankedResult)}
    path = directory / "schemas.json"
    path.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new private directory; must not already exist")
    args = parser.parse_args(argv)
    try:
        create_template(args.output)
    except OSError as exc:
        parser.error(str(exc))
    print(json.dumps({"directory": str(args.output), "status": "empty_template_not_a_benchmark"}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
