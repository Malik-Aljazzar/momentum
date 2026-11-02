// Scroll containers that belong to a tab (keep their position, scroll to top on a second tap).
import type { ComponentChildren, JSX } from "preact"
import { useEffect, useRef } from "preact/hooks"
import { scrollTop, type Tab } from "../nav"

/** Padding for tab content: below the status bar, above the tab bar. */
export const tabPadding = "calc(var(--safe-top) + 12px) 16px calc(var(--safe-bottom) + var(--tabbar) + 24px)"

export function TabScroll({ tab, children, padding = tabPadding, style }: {
  tab: Tab; children: ComponentChildren; padding?: string; style?: JSX.CSSProperties
}) {
  const ref = useRef<HTMLDivElement>(null)
  const first = useRef(true)
  const n = scrollTop.value
  useEffect(() => {
    if (first.current) { first.current = false; return }
    if (n.tab === tab) ref.current?.scrollTo({ top: 0, behavior: "smooth" })
  }, [n.n])
  return <div ref={ref} class="fill scroll" style={{ padding, ...style }}>{children}</div>
}
