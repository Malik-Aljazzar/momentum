// Translates the Android app's texts (android/.../ui/Strings.kt: interface Strings, object De,
// object En) into src/strings/strings.gen.ts, so both clients always say the same.
//
// Simple members (string values, one-expression functions with templates, if/when, Pairs, lists,
// maps) are translated automatically. Members the translator can't handle (cloze sentences,
// lambdas, ...) must exist in src/strings/manual.ts - the TypeScript compiler checks that every
// member of the interface is there, so a new Kotlin string can't be forgotten on the web.
import { readFileSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const web = join(dirname(fileURLToPath(import.meta.url)), "..")
const KT = join(web, "..", "android", "app", "src", "main", "java", "com", "momentum", "app", "ui", "Strings.kt")
const OUT = join(web, "src", "strings", "strings.gen.ts")

const src = readFileSync(KT, "utf8").replaceAll("\r\n", "\n")

function block(header) {
  const start = src.indexOf(header)
  if (start < 0) throw new Error("not found: " + header)
  const end = src.indexOf("\n}\n", start)
  return src.slice(src.indexOf("{", start) + 1, end)
}

// ---------------------------------------------------------------- types

function splitTop(s, sep = ",") {
  const out = []; let depth = 0, cur = ""
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if ("(<[{".includes(ch)) depth++
    if (")]}".includes(ch) || (ch === ">" && s[i - 1] !== "-")) depth--
    if (ch === sep && depth === 0) { out.push(cur); cur = "" } else cur += ch
  }
  if (cur.trim()) out.push(cur)
  return out.map((x) => x.trim())
}

function tsType(t) {
  t = t.trim()
  if (t.endsWith("?")) return tsType(t.slice(0, -1)) + " | null"
  if (t.startsWith("@Composable")) return "() => any"
  const m = t.match(/^(\w+)<(.*)>$/)
  if (m) {
    const args = splitTop(m[2])
    if (m[1] === "List" || m[1] === "Set") return `${wrap(tsType(args[0]))}[]`
    if (m[1] === "Pair") return `[${tsType(args[0])}, ${tsType(args[1])}]`
    if (m[1] === "Map") return `Record<${tsType(args[0])}, ${tsType(args[1])}>`
  }
  switch (t) {
    case "String": return "string"
    case "Int": case "Double": case "Float": case "Long": return "number"
    case "Boolean": return "boolean"
    case "Unit": return "void"
    case "Lang": return "Lang"
    default: return t            // Rule, ClozeBuilder, RuleGaps, DecayGaps: declared in types.ts
  }
}
const wrap = (t) => (t.includes(" ") ? `(${t})` : t)

// Kotlin parameter names that are reserved words in JavaScript
const RESERVED = new Set(["delete", "new", "default", "function", "var", "let", "const", "class", "switch", "case", "this", "void", "typeof", "with", "yield", "enum", "export", "import", "super", "package", "public", "private", "static"])
const jsName = (n) => (RESERVED.has(n) ? n + "_" : n)

function params(list) {
  return splitTop(list).filter(Boolean).map((p) => {
    const m = p.match(/^(\w+)\s*:\s*(.+?)(?:\s*=\s*(.+))?$/s)
    if (!m) throw new Error("param: " + p)
    return { name: jsName(m[1]), type: tsType(m[2]), def: m[3] }
  })
}

// ---------------------------------------------------------------- interface

const iface = block("interface Strings {")
const members = new Map()          // name -> {kind, sig}
for (const raw of iface.split("\n")) {
  const line = raw.trim()
  let m
  if ((m = line.match(/^val (\w+)\s*:\s*(.+)$/))) members.set(m[1], { kind: "val", type: tsType(m[2]) })
  else if ((m = line.match(/^fun (\w+)\((.*)\)\s*(?::\s*(.+))?$/))) {
    members.set(m[1], { kind: "fun", params: params(m[2]), ret: m[3] ? tsType(m[3]) : "void" })
  }
}

// ---------------------------------------------------------------- expression translator

class Fail extends Error {}

function tokenize(s) {
  const toks = []; let i = 0
  const ops = ["?:", "?.", "==", "!=", "<=", ">=", "&&", "||", "->", "..", "(", ")", "{", "}", "[", "]", ",", ".", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", ";", "="]
  while (i < s.length) {
    const c = s[i]
    if (c === "\n") { toks.push({ t: "nl" }); i++; continue }
    if (/\s/.test(c)) { i++; continue }
    if (c === "/" && s[i + 1] === "/") { while (i < s.length && s[i] !== "\n") i++; continue }
    if (c === '"') {
      // string with templates: parts are strings or raw expression source
      const parts = []; let cur = ""; i++
      while (s[i] !== '"') {
        if (i >= s.length) throw new Fail("unterminated string")
        if (s[i] === "\\") { cur += s[i] + s[i + 1]; i += 2; continue }
        if (s[i] === "$" && s[i + 1] === "{") {
          let depth = 1, j = i + 2, inner = ""
          while (depth > 0) {
            if (s[j] === "{") depth++
            if (s[j] === "}") depth--
            if (depth > 0) inner += s[j]
            j++
          }
          parts.push(cur, { expr: inner }); cur = ""; i = j; continue
        }
        if (s[i] === "$" && /[a-zA-Z_]/.test(s[i + 1])) {
          let j = i + 1, name = ""
          while (/\w/.test(s[j])) name += s[j++]
          parts.push(cur, { expr: name }); cur = ""; i = j; continue
        }
        cur += s[i++]
      }
      parts.push(cur); i++
      toks.push({ t: "str", parts }); continue
    }
    if (/[0-9]/.test(c)) {
      let j = i; while (/[0-9._]/.test(s[j]) && !(s[j] === "." && s[j + 1] === ".")) j++
      toks.push({ t: "num", v: s.slice(i, j).replaceAll("_", "").replace(/[fFL]$/, "") }); i = j; continue
    }
    if (/[a-zA-Z_]/.test(c)) {
      let j = i; while (/\w/.test(s[j] ?? "")) j++
      toks.push({ t: "id", v: s.slice(i, j) }); i = j; continue
    }
    const op = ops.find((o) => s.startsWith(o, i))
    if (!op) throw new Fail("char " + c)
    toks.push({ t: "op", v: op }); i += op.length
  }
  return toks
}

function translate(source, scope) {
  const toks = tokenize(source)
  let p = 0
  const peek = (o = 0) => toks[p + o]
  const isOp = (v, o = 0) => peek(o)?.t === "op" && peek(o).v === v
  const isId = (v, o = 0) => peek(o)?.t === "id" && peek(o).v === v
  const skipNl = () => { while (peek()?.t === "nl") p++ }
  const expect = (v) => { skipNl(); if (!isOp(v)) throw new Fail(`expected ${v}`); p++ }

  function str(tok) {
    let out = "`"
    for (const part of tok.parts) {
      if (typeof part === "string") out += part.replaceAll("`", "\\`").replaceAll("${", "\\${").replace(/\\\$/g, "$")
      else out += "${" + translate(part.expr, scope) + "}"
    }
    return out + "`"
  }

  function args() {           // after "("
    const list = []
    skipNl()
    while (!isOp(")")) { list.push(expr()); skipNl(); if (isOp(",")) { p++; skipNl() } }
    p++
    return list
  }

  function primary() {
    skipNl()
    const t = peek()
    if (!t) throw new Fail("eof")
    if (t.t === "str") { p++; return str(t) }
    if (t.t === "num") { p++; return t.v }
    if (isOp("(")) { p++; const e = expr(); expect(")"); return `(${e})` }
    if (t.t === "id") {
      p++
      switch (t.v) {
        case "null": case "true": case "false": return t.v
        case "if": {
          expect("("); const c = expr(); expect(")")
          const a = branch(); skipNl()
          if (!isId("else")) throw new Fail("if without else"); p++
          const b = branch()
          return `((${c}) ? ${a} : ${b})`
        }
        case "when": return when()
        case "listOf": case "setOf": { expect("("); return `[${args().join(", ")}]` }
        case "listOfNotNull": { expect("("); return `[${args().join(", ")}].filter((x) => x != null)` }
        case "mapOf": {
          expect("(")
          const pairs = args().map((a) => { const m = a.replace(/ as \[string, string\]$/, "").match(/^\[(`[^`]*`), ([\s\S]*)\]$/); if (!m) throw new Fail("map key"); return `${JSON.stringify(m[1].slice(1, -1))}: ${m[2]}` })
          return `({${pairs.join(", ")}} as Record<string, string>)`
        }
      }
      if (isOp("(")) {
        p++
        const a = args()
        if (["fmtVal", "fmt"].includes(t.v)) return `${t.v}(${a.join(", ")})`
        if (members.has(t.v)) return `L.${t.v}(${a.join(", ")})`
        throw new Fail("call " + t.v)
      }
      if (isOp("{")) throw new Fail("lambda")
      if (scope.has(jsName(t.v))) return jsName(t.v)
      if (members.has(t.v)) return `L.${t.v}`
      throw new Fail("ident " + t.v)
    }
    throw new Fail("token " + JSON.stringify(t))
  }

  function branch() {
    skipNl()
    if (isOp("{")) {
      p++; skipNl()
      const e = expr(); skipNl()
      if (!isOp("}")) throw new Fail("block with statements"); p++
      return e
    }
    return expr()
  }

  function when() {
    let subject = null
    skipNl()
    if (isOp("(")) { p++; subject = expr(); expect(")") }
    expect("{")
    const cases = []; let otherwise = null
    for (;;) {
      skipNl()
      while (isOp(";")) { p++; skipNl() }
      if (isOp("}")) { p++; break }
      if (isId("else")) { p++; expect("->"); otherwise = branch(); continue }
      const conds = [expr()]
      while ((skipNl(), isOp(","))) { p++; conds.push(expr()) }
      expect("->")
      const body = branch()
      const test = conds.map((c) => (subject ? `${subject} === ${c}` : `(${c})`)).join(" || ")
      cases.push([test, body])
    }
    if (otherwise == null) throw new Fail("when without else")
    return "(" + cases.map(([t, b]) => `(${t}) ? ${b} : `).join("") + otherwise + ")"
  }

  function postfix() {
    let e = primary()
    for (;;) {
      if (isOp(".") || isOp("?.")) {
        const safe = peek().v === "?."; p++
        const name = peek()?.v; p++
        const dot = safe ? "?." : "."
        if (isOp("(")) {
          p++; const a = args()
          const map = { uppercase: "toUpperCase", lowercase: "toLowerCase", joinToString: "join", trim: "trim" }
          if (!(name in map)) throw new Fail("method " + name)
          e = `${e}${dot}${map[name]}(${a.join(", ")})`
        } else if (name === "length" || name === "size") e = `${e}${dot}length`
        else throw new Fail("property " + name)
        continue
      }
      if (isOp("[")) { p++; const k = expr(); expect("]"); e = `${e}[${k}]`; continue }
      return e
    }
  }

  function unary() {
    skipNl()
    if (isOp("!")) { p++; return `!${unary()}` }
    if (isOp("-")) { p++; return `-${unary()}` }
    return postfix()
  }
  function bin(next, opsMap) {
    return () => {
      let e = next()
      for (;;) {
        const t = peek()
        if (t?.t === "op" && t.v in opsMap) { p++; e = `${e} ${opsMap[t.v]} ${next()}`; continue }
        return e
      }
    }
  }
  // the texts only divide whole numbers (Kotlin Int/Long division truncates)
  const mul = () => {
    let e = unary()
    for (;;) {
      const t = peek()
      if (t?.t === "op" && t.v === "/") { p++; e = `Math.trunc(${e} / ${unary()})`; continue }
      if (t?.t === "op" && (t.v === "*" || t.v === "%")) { p++; e = `${e} ${t.v} ${unary()}`; continue }
      return e
    }
  }
  const add = bin(mul, { "+": "+", "-": "-" })
  const cmp = bin(add, { "==": "===", "!=": "!==", "<": "<", ">": ">", "<=": "<=", ">=": ">=" })
  const and = bin(cmp, { "&&": "&&" })
  const or = bin(and, { "||": "||" })
  function elvis() {
    let e = or()
    while (isOp("?:")) { p++; e = `(${e} ?? ${or()})` }
    return e
  }
  function expr() {
    let e = elvis()
    if (isId("to")) { p++; e = `[${e}, ${elvis()}] as [string, string]` }
    return e
  }

  const result = expr()
  skipNl()
  if (p < toks.length) throw new Fail("trailing " + JSON.stringify(peek()))
  return result
}

// ---------------------------------------------------------------- objects

function members_of(body) {
  const out = []; let cur = null
  for (const line of body.split("\n")) {
    if (/^ {4}override /.test(line)) { if (cur) out.push(cur); cur = line.slice(4) }
    else if (/^ {4}private /.test(line)) { if (cur) out.push(cur); cur = null }
    else if (/^ {4}\/\//.test(line) || /^ {4}\/\*\*/.test(line)) continue
    else if (cur != null) cur += "\n" + line
  }
  if (cur) out.push(cur)
  return out
}

const manual = { de: [], en: [] }

function convert(body, lang) {
  const lines = []
  for (const m of members_of(body)) {
    let x
    if ((x = m.match(/^override val (\w+)(?:\s*:\s*[^=]+)?\s*=\s*([\s\S]+)$/))) {
      const [, name, rhs] = x
      if (name === "lang") continue
      try {
        const js = translate(rhs, new Set())
        lines.push({ name, line: /\bL\./.test(js) ? `  get ${name}() { return ${js} },` : `  ${name}: ${js},` })
      } catch (e) { if (!(e instanceof Fail)) throw e; manual[lang].push(name) }
      continue
    }
    if ((x = m.match(/^override fun (\w+)\(([^)]*)\)\s*(?::\s*[^=]+)?=\s*([\s\S]+)$/))) {
      const [, name, plist, rhs] = x
      const decl = members.get(name)
      if (!decl) throw new Error("not in interface: " + name)
      const ps = params(plist)
      if (/^with\(|^run|^\{/.test(rhs.trim())) { manual[lang].push(name); continue }
      try {
        const js = translate(rhs, new Set(ps.map((q) => q.name)))
        const sig = ps.map((q, i) => {
          const def = decl.params[i]?.def
          return def != null ? `${q.name}: ${q.type} = ${translate(def, new Set())}` : `${q.name}: ${q.type}`
        }).join(", ")
        lines.push({ name, line: `  ${name}: (${sig}): ${decl.ret} => ${js},` })
      } catch (e) { if (!(e instanceof Fail)) throw e; manual[lang].push(name) }
      continue
    }
    const nm = m.match(/^override (?:val|fun) (\w+)/)
    if (nm) { manual[lang].push(nm[1]); continue }
    throw new Error("unparsed member: " + m.slice(0, 80))
  }
  return lines
}

const de = convert(block("object De : Strings {"), "de")
const en = convert(block("object En : Strings {"), "en")

const ifaceLines = [...members].filter(([n]) => n !== "lang").map(([name, d]) => d.kind === "val"
  ? `  readonly ${name}: ${d.type}`
  : `  ${name}(${d.params.map((q) => `${q.name}${q.def != null ? "?" : ""}: ${q.type}`).join(", ")}): ${d.ret}`)

const manualKeys = [...new Set([...manual.de, ...manual.en])].sort()
const keep = (lines) => lines.filter((l) => !manualKeys.includes(l.name)).map((l) => l.line)
// import only what the generated code uses (unused imports fail the type check)
const body = [...ifaceLines, ...keep(de), ...keep(en)].join("\n")
const types = ["ClozeBuilder", "DecayGaps", "Lang", "Rule", "RuleGaps"].filter((t) => t === "Lang" || new RegExp(`\\b${t}\\b`).test(body))
const fns = ["fmt", "fmtVal"].filter((f) => new RegExp(`\\b${f}\\(`).test(body))
const imports = `import type { ${types.join(", ")} } from "./types"\n` + (fns.length ? `import { ${fns.join(", ")} } from "../format"\n` : "")
const out = `// GENERATED by scripts/strings.mjs from android/.../ui/Strings.kt - do not edit.
// Change texts in Strings.kt (both clients use them); members listed in ManualKeys are written
// by hand in manual.ts.
/* eslint-disable */
${imports}
export interface Strings {
  readonly lang: Lang
${ifaceLines.join("\n")}
}

/** Members that need a hand-written version in manual.ts. */
export type ManualKeys = ${manualKeys.map((k) => JSON.stringify(k)).join(" | ") || "never"}

type Auto = Omit<Strings, ManualKeys | "lang">

export function autoDe(L: Strings): Auto {
  return {
${keep(de).map((l) => "  " + l).join("\n")}
  }
}

export function autoEn(L: Strings): Auto {
  return {
${keep(en).map((l) => "  " + l).join("\n")}
  }
}
`
writeFileSync(OUT, out)
console.log(`strings: ${members.size} members, ${manualKeys.length} hand-written: ${manualKeys.join(", ")}`)
