// 8-bit call sounds made with Web Audio (no files): the ringing of an incoming call, the ringback while it rings at
// the other end, the beep at the end. Android plays the phone's own ringtone instead (ui/Call.kt).
type Note = [freq: number, start: number, length: number]

const RING: Note[] = [[659, 0, .09], [784, .11, .09], [1047, .22, .09], [784, .33, .09],
  [659, .6, .09], [784, .71, .09], [1047, .82, .09], [784, .93, .09]]
const RINGBACK: Note[] = [[440, 0, .9]]
const END: Note[] = [[784, 0, .09], [523, .12, .09], [392, .24, .2]]

let ctx: AudioContext | null = null
let timer: ReturnType<typeof setInterval> | undefined
let playing: OscillatorNode[] = []

function context(): AudioContext | null {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!ctx && Ctx) ctx = new Ctx()
    // "suspended" until the first tap, iOS: "interrupted" after a phone call / when the app was away
    if (ctx && ctx.state !== "running" && ctx.state !== "closed") void ctx.resume().catch(() => {})
  } catch { /* no audio */ }
  return ctx
}

function play(notes: Note[], wave: OscillatorType, volume: number) {
  const c = context()
  // not running (no tap yet): nothing queued - a stopped clock would play every queued round at once after a tap
  if (!c || c.state !== "running") return
  const t0 = c.currentTime + .02
  for (const [freq, start, length] of notes) {
    const osc = c.createOscillator(), gain = c.createGain()
    osc.type = wave
    osc.frequency.value = freq
    gain.gain.setValueAtTime(volume, t0 + start)
    gain.gain.setValueAtTime(0, t0 + start + length)
    osc.connect(gain).connect(c.destination)
    osc.start(t0 + start)
    osc.stop(t0 + start + length + .02)
    playing.push(osc)
    osc.onended = () => { playing = playing.filter((o) => o !== osc) }
  }
}

function loop(notes: Note[], every: number, wave: OscillatorType, volume: number, buzz = false) {
  tones.stop()
  const once = () => { play(notes, wave, volume); if (buzz) navigator.vibrate?.([300, 150, 300]) }
  once()
  timer = setInterval(once, every)
}

export const tones = {
  /** iOS lets a page make sounds only after a tap: called from the first tap anywhere (main.tsx). */
  unlock() { context() },
  ring() { loop(RING, 2400, "square", .07, true) },
  ringback() { loop(RINGBACK, 3000, "triangle", .08) },
  end() { tones.stop(); play(END, "square", .06) },
  stop() {
    if (timer) clearInterval(timer)
    timer = undefined
    for (const o of playing) { try { o.stop() } catch { /* done already */ } }
    playing = []
    navigator.vibrate?.(0)
  },
}
