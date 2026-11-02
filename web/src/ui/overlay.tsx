// Full-screen layers above everything (photo crop, text-on-photo editor), like RootOverlayHost in
// the Android app: open() puts a component on top, the app root draws it.
import type { ComponentChildren } from "preact"
import { signal } from "@preact/signals"

const layers = signal<{ id: number; node: () => ComponentChildren }[]>([])
let next = 1

/** Shows [node] above everything; returns the function that closes it. */
export function openOverlay(node: (close: () => void) => ComponentChildren): () => void {
  const id = next++
  const close = () => { layers.value = layers.value.filter((l) => l.id !== id) }
  layers.value = [...layers.value, { id, node: () => node(close) }]
  return close
}

export function OverlayHost() {
  return <>{layers.value.map((l) => <div key={l.id} class="fill" style={{ zIndex: 70 }}>{l.node()}</div>)}</>
}
