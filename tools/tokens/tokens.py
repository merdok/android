#!/usr/bin/env python3
"""Emit the UI tokens in tokens.json as Kotlin and CSS.

    python3 tools/tokens/tokens.py          write every output
    python3 tools/tokens/tokens.py --check  exit 1 when an output differs from what tokens.json gives

Outputs:
  * app/src/main/kotlin/io/panelassistant/android/PanelTokens.kt — every native colour, the scale rule
    and dp();
  * tools/tokens/tokens.css — the web custom properties, the copy Panel Assistant takes;
  * the same CSS between the `tokens:begin` and `tokens:end` markers at the top of
    app/src/main/assets/info.css, so `:8888` gets its tokens without a second render-blocking request.

tokens.json is the only place a value is written. Its sections:
  * `scale.baselineEdgePx` — the shortest edge, in pixels at density 1, that native baseline sizes
    are drawn for;
  * `web.themed` — `{name: {dark, light}}`, emitted per theme; `web.fixed` — `{name: value}`, the
    same in both themes. Any CSS colour.
  * `native.<group>` — `{"type": Kotlin class, "format": "hex"|"int", "themed": bool, "doc", "fields"}`.
    A themed group becomes a data class with DARK and LIGHT instances; a fixed group an object of
    constants. Field values are `#rgb`, `#rrggbb` or `#rrggbbaa` (CSS order); a field may be
    `{"value"|"dark"/"light", "doc"}`.
"""
from __future__ import annotations

import json
import re
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/tokens/tokens.json"
KOTLIN = ROOT / "app/src/main/kotlin/io/panelassistant/android/PanelTokens.kt"
CSS = ROOT / "tools/tokens/tokens.css"
INFO_CSS = ROOT / "app/src/main/assets/info.css"
BEGIN = "/* tokens:begin"
END = "/* tokens:end */"
NOTICE = "Generated from tools/tokens/tokens.json by tools/tokens/tokens.py; do not edit."
HEX = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def css(tokens: dict) -> str:
    themed, fixed = tokens["web"]["themed"], tokens["web"]["fixed"]

    def block(selector: str, scheme: str, values: dict) -> str:
        body = "".join(f"\n  --{name}:{value};" for name, value in values.items())
        return f"{selector}{{color-scheme:{scheme};{body}}}"

    dark = {name: pair["dark"] for name, pair in themed.items()}
    light = {name: pair["light"] for name, pair in themed.items()}
    return "\n".join([
        f"/* {NOTICE}",
        "   Dark is the default. The light values apply when the viewing browser prefers light, unless",
        "   ?theme=dark pinned dark (`data-theme` on the document, `theme` on a Panel Assistant host), and",
        "   whenever ?theme=light pinned light. `:host` serves a shadow root, `:root` a page. */",
        block(":root,:host", "dark", {**dark, **fixed}),
        "@media (prefers-color-scheme: light){"
        + block(":root:not([data-theme=dark]),:host(:not([theme=dark]))", "light", light) + "}",
        block(":root[data-theme=light],:host([theme=light])", "light", light),
        "",
    ])


def argb(value: str, where: str) -> str:
    if not HEX.match(value):
        raise SystemExit(f"tokens.json: {where} is {value!r}; a native colour is #rgb, #rrggbb or #rrggbbaa")
    digits = value[1:].upper()
    if len(digits) == 3:
        digits = "".join(digit * 2 for digit in digits)
    alpha = digits[6:] or "FF"
    return f"0x{alpha}{digits[:6]}.toInt()"


def constant(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


def kdoc(text: str | None, indent: str) -> list[str]:
    if not text:
        return []
    lines = textwrap.wrap(text, 100 - len(indent) - 3)
    if len(lines) == 1 and len(indent) + len(lines[0]) + 7 <= 100:
        return [f"{indent}/** {lines[0]} */"]
    return [f"{indent}/**", *(f"{indent} * {line}" for line in lines), f"{indent} */"]


def kotlin(tokens: dict) -> str:
    out = [
        f"// {NOTICE}",
        "package io.panelassistant.android",
        "",
        "/**",
        " * The one source of every colour a native panel screen draws, and of the scale rule that sizes",
        " * them. Imports nothing, so the unit gate can read it directly.",
        " */",
        "internal object PanelTokens {",
        "    /** The shortest edge, in pixels at density 1, that every baseline size is drawn for. */",
        f"    const val BASELINE_EDGE_PX = {float(tokens['scale']['baselineEdgePx'])}f",
        "",
        "    /**",
        "     * How much to scale a baseline size: grows with a small display, but never past the display's",
        "     * logical density, because a wall panel is read from across a room and a notice must not keep",
        "     * taking a larger share of a bigger screen (on a 1920x1200 panel an uncapped notice took about",
        "     * 40% of the height). Capping at the density honours a logical-density override. A non-positive",
        "     * density counts as 1, because collapsing a notice to nothing is worse than an oversized one.",
        "     */",
        "    fun scale(shortestEdgePx: Float, density: Float): Float =",
        "        minOf(shortestEdgePx / BASELINE_EDGE_PX, if (density > 0f) density else 1f)",
        "",
        "    /** [value] dp in whole pixels at [density], truncated. */",
        "    fun dp(value: Int, density: Float): Int = (value * density).toInt()",
    ]
    classes: list[str] = []
    for group, spec in tokens["native"].items():
        fmt, kind = spec["format"], spec.get("type")
        if fmt not in ("hex", "int"):
            raise SystemExit(f"tokens.json: native.{group}.format is {fmt!r}")

        def literal(value: str, where: str) -> str:
            argb(value, where)  # validates
            return f'"{value}"' if fmt == "hex" else argb(value, where)

        fields = {name: (field if isinstance(field, dict) else {"value": field})
                  for name, field in spec["fields"].items()}
        out.append("")
        out += kdoc(spec.get("doc"), "    ")
        if spec["themed"]:
            kotlin_type = "String" if fmt == "hex" else "Int"
            classes += kdoc(spec.get("doc"), "")
            classes.append(f"internal data class {kind}(")
            for name, field in fields.items():
                classes += kdoc(field.get("doc"), "    ")
                classes.append(f"    val {name}: {kotlin_type},")
            classes.append(")")
            classes.append("")
            out.append(f"    object {group[0].upper()}{group[1:]} {{")
            for theme in ("dark", "light"):
                out.append(f"        val {theme.upper()} = {kind}(")
                out += [f"            {name} = {literal(field[theme], f'native.{group}.{name}.{theme}')},"
                        for name, field in fields.items()]
                out.append("        )")
            out.append("    }")
        else:
            out.append(f"    object {group[0].upper()}{group[1:]} {{")
            for name, field in fields.items():
                out += kdoc(field.get("doc"), "        ")
                out.append(f"        const val {constant(name)} = {literal(field['value'], f'native.{group}.{name}')}")
            out.append("    }")
    out.append("}")
    out.append("")
    return "\n".join(out + classes).rstrip("\n") + "\n"


def with_region(text: str, region: str) -> str:
    start, end = text.find(BEGIN), text.find(END)
    if start < 0 or end < start:
        raise SystemExit(f"{INFO_CSS.relative_to(ROOT)}: no `{BEGIN} … {END}` region")
    return text[:start] + BEGIN + " */\n" + region + text[end:]


def main(argv: list[str]) -> int:
    check = argv[1:] == ["--check"]
    if argv[1:] not in ([], ["--check"]):
        print(__doc__, file=sys.stderr)
        return 2
    tokens = json.loads(SOURCE.read_text(encoding="utf-8"))
    sheet = css(tokens)
    wanted = {
        KOTLIN: kotlin(tokens),
        CSS: sheet,
        INFO_CSS: with_region(INFO_CSS.read_text(encoding="utf-8"), sheet),
    }
    stale = [path for path, text in wanted.items()
             if not path.exists() or path.read_text(encoding="utf-8") != text]
    if check:
        for path in stale:
            print(f"tokens: {path.relative_to(ROOT)} differs from tools/tokens/tokens.json; "
                  "run python3 tools/tokens/tokens.py", file=sys.stderr)
        return 1 if stale else 0
    for path in stale:
        path.write_text(wanted[path], encoding="utf-8")
        print(f"tokens: wrote {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
