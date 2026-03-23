#!/usr/bin/env python3

import argparse
import re
import sys
from pathlib import Path
from typing import Optional
from xml.sax.saxutils import escape


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def block_present(text: str, begin: str, end: str) -> bool:
    return begin in text and end in text


def replace_managed_block(text: str, begin: str, end: str, block: str) -> str:
    pattern = re.compile(re.escape(begin) + r".*?" + re.escape(end), re.DOTALL)
    return pattern.sub(block, text, count=1)


def replace_existing_algolia_module(text: str, block: str) -> str:
    patterns = [
        re.compile(r"<module\b[^>]*\bid=(['\"])algolia-index\1[^>]*/>", re.DOTALL),
        re.compile(r"<module\b[^>]*\bid=(['\"])algolia-index\1[^>]*>.*?</module>", re.DOTALL),
    ]
    for pattern in patterns:
        if pattern.search(text):
            return pattern.sub(block, text, count=1)
    return text


def replace_existing_dependency(text: str, artifact_id: str, relative_path: str, block: str) -> str:
    dependency_pattern = re.compile(r"<dependency>\s*.*?</dependency>", re.DOTALL)
    for match in dependency_pattern.finditer(text):
        snippet = match.group(0)
        if artifact_id in snippet or relative_path in snippet or "exist-algolia-index" in snippet:
            return text[:match.start()] + block + text[match.end():]
    return text


def indent_for_line(text: str, needle: str, extra: str = "    ") -> str:
    match = re.search(rf"(^[ \t]*){needle}", text, re.MULTILINE)
    if not match:
        return extra
    return match.group(1) + extra


def update_conf(path: Path, application_id: str, admin_api_key: str, begin: str, end: str) -> None:
    text = read_text(path)
    indent = indent_for_line(text, r"<modules\b")
    module = (
        f'{indent}{begin}\n'
        f'{indent}<module id="algolia-index" class="org.humanistika.exist.index.algolia.AlgoliaIndex" '
        f'application-id="{escape(application_id)}" admin-api-key="{escape(admin_api_key)}"/>\n'
        f'{indent}{end}'
    )

    if block_present(text, begin, end):
        updated = replace_managed_block(text, begin, end, module)
    else:
        updated = replace_existing_algolia_module(text, module)
        if updated == text:
            modules_match = re.search(r"(<modules\b[^>]*>)", text)
            if not modules_match:
                raise SystemExit(f"Could not locate <modules> element in {path}")
            insert_at = modules_match.end()
            updated = text[:insert_at] + "\n" + module + text[insert_at:]

    write_text(path, updated)


def verify_conf(path: Path, application_id: Optional[str], admin_api_key: Optional[str]) -> None:
    text = read_text(path)
    required = [
        'id="algolia-index"',
        'class="org.humanistika.exist.index.algolia.AlgoliaIndex"',
    ]
    if application_id is not None:
        required.append(f'application-id="{application_id}"')
    if admin_api_key is not None:
        required.append(f'admin-api-key="{admin_api_key}"')

    missing = [needle for needle in required if needle not in text]
    if missing:
        raise SystemExit(f"conf.xml is missing expected values: {', '.join(missing)}")


def update_startup(
    path: Path,
    group_id: str,
    artifact_id: str,
    version: str,
    relative_path: str,
    begin: str,
    end: str,
) -> None:
    text = read_text(path)
    indent = indent_for_line(text, r"<group\b[^>]*name=(['\"])user\1", "")
    dependency_indent = indent + "    "
    value_indent = dependency_indent + "    "
    block = (
        f"{dependency_indent}{begin}\n"
        f"{dependency_indent}<dependency>\n"
        f"{value_indent}<groupId>{escape(group_id)}</groupId>\n"
        f"{value_indent}<artifactId>{escape(artifact_id)}</artifactId>\n"
        f"{value_indent}<version>{escape(version)}</version>\n"
        f"{value_indent}<relativePath>{escape(relative_path)}</relativePath>\n"
        f"{dependency_indent}</dependency>\n"
        f"{dependency_indent}{end}"
    )

    if block_present(text, begin, end):
        updated = replace_managed_block(text, begin, end, block)
    else:
        updated = replace_existing_dependency(text, artifact_id, relative_path, block)
        if updated == text:
            group_match = re.search(r"(<group\b[^>]*name=(['\"])user\2[^>]*>)", text)
            if group_match:
                closing_match = re.search(r"</group>", text[group_match.end():])
                if not closing_match:
                    raise SystemExit(f"Could not locate </group> for user group in {path}")
                insert_at = group_match.end() + closing_match.start()
                updated = text[:insert_at] + "\n" + block + "\n" + text[insert_at:]
            else:
                closing_root = re.search(r"</[^>]+>\s*$", text, re.DOTALL)
                if not closing_root:
                    raise SystemExit(f"Could not locate closing root element in {path}")
                insert_at = closing_root.start()
                updated = text[:insert_at] + "\n" + block + "\n" + text[insert_at:]

    write_text(path, updated)


def verify_startup(path: Path, artifact_id: str, version: str, relative_path: str) -> None:
    text = read_text(path)
    required = [
        f"<artifactId>{artifact_id}</artifactId>",
        f"<version>{version}</version>",
        f"<relativePath>{relative_path}</relativePath>",
    ]
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise SystemExit(f"startup.xml is missing expected values: {', '.join(missing)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    conf_update = subparsers.add_parser("update-conf")
    conf_update.add_argument("path", type=Path)
    conf_update.add_argument("application_id")
    conf_update.add_argument("admin_api_key")
    conf_update.add_argument("begin")
    conf_update.add_argument("end")

    conf_verify = subparsers.add_parser("verify-conf")
    conf_verify.add_argument("path", type=Path)
    conf_verify.add_argument("--application-id")
    conf_verify.add_argument("--admin-api-key")

    startup_update = subparsers.add_parser("update-startup")
    startup_update.add_argument("path", type=Path)
    startup_update.add_argument("group_id")
    startup_update.add_argument("artifact_id")
    startup_update.add_argument("version")
    startup_update.add_argument("relative_path")
    startup_update.add_argument("begin")
    startup_update.add_argument("end")

    startup_verify = subparsers.add_parser("verify-startup")
    startup_verify.add_argument("path", type=Path)
    startup_verify.add_argument("artifact_id")
    startup_verify.add_argument("version")
    startup_verify.add_argument("relative_path")

    args = parser.parse_args()

    if args.command == "update-conf":
        update_conf(args.path, args.application_id, args.admin_api_key, args.begin, args.end)
    elif args.command == "verify-conf":
        verify_conf(args.path, args.application_id, args.admin_api_key)
    elif args.command == "update-startup":
        update_startup(
            args.path,
            args.group_id,
            args.artifact_id,
            args.version,
            args.relative_path,
            args.begin,
            args.end,
        )
    elif args.command == "verify-startup":
        verify_startup(args.path, args.artifact_id, args.version, args.relative_path)
    else:
        raise SystemExit(f"Unknown command: {args.command}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
