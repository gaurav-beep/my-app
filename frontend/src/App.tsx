import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'

type UserInfo = { name?: string; email?: string; picture?: string }
type ToastState = { msg: string } | null

type Order = {
  id: number; symbol: string; side: string; qty: number; status: string; reason: string | null
  decidedOn: string; fillPrice: number | null; fees: number | null; amount?: number | null
}
type PaperView = {
  cash: number; startCash: number; startBenchmark: number | null; equity: number; returnPct: number; benchmarkPct: number | null
  positions: { symbol: string; qty: number; avgCost: number; price: number | null; value: number | null; pnl: number | null }[]
  orders: Order[]
  history: { day: string; equity: number; benchmark: number | null }[]
  lastSummary: string | null; lastDecisionOn: string | null; autoTrade: boolean; strategy: string | null
}

const BASE = '/crypto-paper'
const WAKING = 'Waking up the server. This can take up to a minute the first time...'
const PALETTE = ['#f7931a', '#627eea', '#f3ba2f', '#3b82f6', '#14b8a6', '#c2a633', '#8b5cf6', '#ef4444', '#ec4899', '#2a5ada']

const inr = (n: number) => n.toLocaleString('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 })
const qtyText = (n: number) => n.toLocaleString('en-IN', { maximumFractionDigits: 8 })
const pctText = (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

declare global {
  interface Window { google?: any }
}

function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
}

async function api(path: string, init: RequestInit = {}) {
  const safe = (init.method ?? 'GET') === 'GET'
  for (let attempt = 0; ; attempt++) {
    const token = localStorage.getItem('token')
    try {
      const res = await fetch(`/api${path}`, {
        ...init,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      })
      if ([502, 503, 504].includes(res.status) && attempt < 10) {
        await sleep(5000)
        continue
      }
      if (res.status === 401 && !path.startsWith('/auth/')) {
        clearSession()
        window.location.reload()
      }
      return res
    } catch (e) {
      if (!safe || attempt >= 10) throw e
      await sleep(5000)
    }
  }
}

function useSlow(active: boolean, ms = 3000) {
  const [slow, setSlow] = useState(false)
  useEffect(() => {
    if (!active) return setSlow(false)
    const t = setTimeout(() => setSlow(true), ms)
    return () => clearTimeout(t)
  }, [active, ms])
  return slow
}

function Logo() {
  return (
    <span className="logo" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="3 17 9 11 13 15 21 7" />
        <polyline points="15 7 21 7 21 13" />
      </svg>
    </span>
  )
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const slow = useSlow(loading || busy)
  const buttonRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    const init = async () => {
      const cfg = await (await api('/auth/config')).json()
      if (!cfg.googleClientId) return setError('Google sign-in is not configured yet')
      await new Promise<void>((resolve, reject) => {
        if (window.google?.accounts) return resolve()
        const s = document.createElement('script')
        s.src = 'https://accounts.google.com/gsi/client'
        s.onload = () => resolve()
        s.onerror = () => reject(new Error('Could not load Google sign-in. Check your connection.'))
        document.head.appendChild(s)
      })
      if (cancelled || !buttonRef.current) return
      window.google.accounts.id.initialize({
        client_id: cfg.googleClientId,
        callback: async (resp: { credential: string }) => {
          setBusy(true)
          setError('')
          try {
            const res = await api('/auth/google', { method: 'POST', body: JSON.stringify({ credential: resp.credential }) })
            if (!res.ok) throw new Error('Google sign-in was rejected. Please try again.')
            const data = await res.json()
            localStorage.setItem('token', data.token)
            localStorage.setItem('user', JSON.stringify(data.user))
            onLogin()
          } catch (err) {
            setError((err as Error).message)
          } finally {
            setBusy(false)
          }
        },
      })
      window.google.accounts.id.renderButton(buttonRef.current, { theme: 'outline', size: 'large', text: 'continue_with', width: 280 })
    }
    init()
      .catch(err => setError((err as Error).message || 'Could not reach the server. Please try again.'))
      .finally(() => setLoading(false))
    return () => { cancelled = true }
  }, [onLogin])

  return (
    <main className="login">
      <div className="login-card">
        <div className="login-mark"><Logo /></div>
        <h1>AI Crypto Lab</h1>
        <p className="lead">Practise crypto trading with virtual rupees. An AI studies the market every few hours and suggests trades, and you test your own ideas risk-free.</p>
        <ul className="points">
          <li>Virtual money only, nothing real is ever at stake</li>
          <li>Live CoinDCX prices, open 24 hours a day</li>
          <li>Approve each AI trade, or let it trade for you</li>
        </ul>
        <div ref={buttonRef} className="gbtn" />
        {(loading || busy) && <p className="muted small">{slow ? WAKING : 'Loading...'}</p>}
        {error && <p className="error">{error}</p>}
      </div>
    </main>
  )
}

function Coin({ symbol }: { symbol: string }) {
  const i = [...symbol].reduce((a, c) => a + c.charCodeAt(0), 0) % PALETTE.length
  return <span className="coin" style={{ background: PALETTE[i] }}>{symbol.slice(0, 3)}</span>
}

function Chart({ view }: { view: PaperView }) {
  const pts = [{ equity: view.startCash, benchmark: view.startBenchmark }, ...view.history]
  if (pts.length < 2) return <p className="chart-empty">Your chart appears after the first daily snapshot.</p>
  const mine = pts.map(p => (p.equity / view.startCash) * 100)
  const bench = pts.map(p => (view.startBenchmark && p.benchmark ? (p.benchmark / view.startBenchmark) * 100 : NaN))
  const all = [...mine, ...bench.filter(v => !Number.isNaN(v)), 100]
  const lo = Math.min(...all)
  const hi = Math.max(...all)
  const w = 320
  const h = 96
  const pad = 6
  const x = (i: number) => (i / (pts.length - 1)) * w
  const y = (v: number) => pad + (h - pad * 2) - ((v - lo) / (hi - lo || 1)) * (h - pad * 2)
  const line = (vals: number[]) => vals.map((v, i) => (Number.isNaN(v) ? '' : `${x(i).toFixed(1)},${y(v).toFixed(1)}`)).filter(Boolean).join(' ')
  const area = `${line(mine)} ${w},${h} 0,${h}`
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${w} ${h}`} width="100%" height="96" preserveAspectRatio="none" role="img" aria-label="AI portfolio versus Bitcoin">
        <defs>
          <linearGradient id="fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#fff" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#fff" stopOpacity="0" />
          </linearGradient>
        </defs>
        <line x1="0" x2={w} y1={y(100)} y2={y(100)} stroke="#fff" strokeOpacity="0.25" strokeDasharray="3 4" />
        <polygon points={area} fill="url(#fill)" />
        <polyline points={line(bench)} fill="none" stroke="#fff" strokeOpacity="0.55" strokeWidth="1.5" strokeDasharray="4 3" vectorEffect="non-scaling-stroke" />
        <polyline points={line(mine)} fill="none" stroke="#fff" strokeWidth="2.5" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="legend"><span><i className="solid" /> AI portfolio</span><span><i className="dashed" /> Bitcoin</span></div>
    </div>
  )
}

function Section({ title, hint, children, defaultOpen = false }: { title: string; hint?: string; children: ReactNode; defaultOpen?: boolean }) {
  return (
    <details className="card fold" open={defaultOpen}>
      <summary><span>{title}</span>{hint && <small>{hint}</small>}</summary>
      <div className="fold-body">{children}</div>
    </details>
  )
}

function Dashboard({ showToast }: { showToast: (t: { msg: string }) => void }) {
  const [status, setStatus] = useState<{ available: boolean; started: boolean } | null>(null)
  const [view, setView] = useState<PaperView | null>(null)
  const [busy, setBusy] = useState(false)
  const [amount, setAmount] = useState('10000')
  const [extra, setExtra] = useState('')
  const [strategy, setStrategy] = useState('')
  const slow = useSlow(status === null)

  const load = useCallback(async () => {
    const st = await (await api(`${BASE}/status`)).json()
    setStatus(st)
    if (st.started) {
      const res = await api(BASE)
      if (res.ok) {
        const v: PaperView = await res.json()
        setView(v)
        setStrategy(s => (s === '' ? v.strategy ?? '' : s))
      }
    } else {
      setView(null)
    }
  }, [])
  useEffect(() => { load().catch(() => setStatus({ available: false, started: false })) }, [load])
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') load().catch(() => undefined) }
    const t = setInterval(refresh, 60000)
    document.addEventListener('visibilitychange', refresh)
    return () => { clearInterval(t); document.removeEventListener('visibilitychange', refresh) }
  }, [load])

  const act = async (path: string, ok?: string, body?: object) => {
    setBusy(true)
    try {
      const res = await api(path, { method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}) })
      if (!res.ok) {
        const msg = res.status === 400 ? 'Enter an amount between 1,000 and 1,00,00,000.' : 'Something went wrong. Please try again.'
        return showToast({ msg })
      }
      const msg = ok ?? (await res.json().catch(() => ({}))).message
      if (msg) showToast({ msg })
      await load()
    } finally {
      setBusy(false)
    }
  }
  const reset = async () => {
    if (!window.confirm('Reset your paper account? This deletes the virtual money, trades and history.')) return
    setStrategy('')
    await act(`${BASE}/reset`, 'Paper account reset')
  }

  if (!status) {
    return (
      <div className="stack">
        <div className="skeleton hero-skeleton" />
        <div className="skeleton row-skeleton" />
        <div className="skeleton row-skeleton" />
        {slow && <p className="muted small center">{WAKING}</p>}
      </div>
    )
  }
  if (!status.available) {
    return <section className="card"><p className="muted">The AI needs a free Gemini API key on the server and is not set up yet.</p></section>
  }

  if (!status.started || !view) {
    return (
      <section className="card start">
        <h2>Start your paper account</h2>
        <p className="muted">Pick any amount of virtual rupees. Every 4 hours an AI looks at 10 popular coins and suggests trades. Orders fill straight away at the live CoinDCX price, with a small cost and slippage added so it feels real.</p>
        <label className="field">
          <span>Starting amount</span>
          <div className="input-wrap">
            <b>₹</b>
            <input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} aria-label="Starting amount" />
          </div>
          <small className="muted">From 1,000 to 1,00,00,000. You can add more any time.</small>
        </label>
        <div className="chips">
          {[10000, 50000, 100000].map(v => <button key={v} type="button" className="chip" onClick={() => setAmount(String(v))}>{inr(v).replace('.00', '')}</button>)}
        </div>
        <button className="btn primary big" disabled={busy || !amount} onClick={() => act(`${BASE}/start`, 'Paper account created', { amount: Number(amount) })}>
          {busy ? 'Starting...' : 'Start paper trading'}
        </button>
        <p className="muted small">A learning simulation, not investment advice. Crypto is very volatile and AI results are unreliable. India&apos;s 1% TDS and crypto tax are not modelled.</p>
      </section>
    )
  }

  const suggestions = view.orders.filter(o => o.status === 'SUGGESTED')
  const history = view.orders.filter(o => o.status !== 'SUGGESTED')
  const diff = view.benchmarkPct == null ? null : view.returnPct - view.benchmarkPct
  const holdingsValue = view.equity - view.cash
  const up = view.returnPct >= 0

  return (
    <div className="stack">
      <section className="hero">
        <div className="hero-top">
          <span>Portfolio value</span>
          <span className={`pill ${up ? 'up' : 'down'}`}>{up ? '▲' : '▼'} {pctText(view.returnPct)}</span>
        </div>
        <div className="hero-value">{inr(view.equity)}</div>
        <div className="hero-sub">
          {view.benchmarkPct != null && <span>Bitcoin {pctText(view.benchmarkPct)}</span>}
          {diff != null && <span className="chip-soft">{diff >= 0 ? 'Beating Bitcoin by' : 'Behind Bitcoin by'} {Math.abs(diff).toFixed(2)} pts</span>}
        </div>
        <Chart view={view} />
      </section>

      <div className="tiles">
        <div className="tile"><small>Cash</small><b>{inr(view.cash)}</b></div>
        <div className="tile"><small>In coins</small><b>{inr(holdingsValue)}</b></div>
        <div className="tile"><small>Started with</small><b>{inr(view.startCash)}</b></div>
      </div>

      <section className="card ai">
        <div className="ai-head">
          <div>
            <h2>AI assistant</h2>
            <small className="muted">Checks the market every 4 hours</small>
          </div>
          <label className="switch" title="Let the AI trade for you">
            <input type="checkbox" checked={view.autoTrade} disabled={busy} aria-label="Auto-trade"
              onChange={e => act(`${BASE}/auto`, e.target.checked ? 'Auto-trade on' : 'Auto-trade off', { enabled: e.target.checked })} />
            <span className="track"><span className="knob" /></span>
            <span className="switch-label">Auto-trade</span>
          </label>
        </div>
        <div className="bubble">{view.lastSummary || 'No decision yet. Tap the button to ask the AI for its first view.'}</div>
        <p className="muted small">{view.autoTrade ? 'The AI trades for you at the live price.' : 'Auto-trade is off. The AI only suggests, and you approve each trade.'}</p>
        <button className="btn primary" disabled={busy} onClick={() => act(`${BASE}/run`)}>{busy ? 'Asking the AI...' : 'Ask the AI now'}</button>
      </section>

      {suggestions.length > 0 && (
        <section className="stack">
          <h3 className="section-title">Waiting for your decision <span className="count">{suggestions.length}</span></h3>
          {suggestions.map(o => (
            <div key={o.id} className="card suggestion">
              <div className="row between">
                <div className="row gap"><Coin symbol={o.symbol} /><div><b>{o.side === 'BUY' ? 'Buy' : 'Sell'} {o.symbol}</b><small className="muted block">{qtyText(o.qty)} coins{o.amount != null ? `, about ${inr(o.amount)}` : ''}</small></div></div>
                <span className={`badge ${o.side === 'BUY' ? 'buy' : 'sell'}`}>{o.side}</span>
              </div>
              {o.reason && <p className="reason">{o.reason}</p>}
              <div className="row gap">
                <button className="btn primary" disabled={busy} onClick={() => act(`${BASE}/orders/${o.id}/approve`, 'Approved and filled at the live price')}>Approve</button>
                <button className="btn" disabled={busy} onClick={() => act(`${BASE}/orders/${o.id}/decline`, 'Declined')}>Decline</button>
              </div>
            </div>
          ))}
        </section>
      )}

      <section className="stack">
        <h3 className="section-title">Your coins</h3>
        {view.positions.length === 0 && <div className="card empty"><b>No coins yet</b><small className="muted">Approve a suggestion, or turn on auto-trade, and your holdings show up here.</small></div>}
        {view.positions.map(p => (
          <div key={p.symbol} className="card holding">
            <div className="row gap"><Coin symbol={p.symbol} /><div><b>{p.symbol}</b><small className="muted block">{qtyText(p.qty)} · avg {inr(p.avgCost)}</small></div></div>
            <div className="right">
              <b>{p.value != null ? inr(p.value) : 'n/a'}</b>
              {p.pnl != null && <span className={`pnl ${p.pnl >= 0 ? 'up' : 'down'}`}>{p.pnl >= 0 ? '+' : '-'}{inr(Math.abs(p.pnl))}</span>}
            </div>
          </div>
        ))}
      </section>

      <Section title="Recent activity" hint={history.length ? `${history.length} trades` : undefined}>
        {history.length === 0 && <p className="muted small">Nothing yet.</p>}
        {history.map(o => (
          <div key={o.id} className="activity">
            <div className="row between">
              <div className="row gap"><Coin symbol={o.symbol} /><div><b>{o.side === 'BUY' ? 'Bought' : 'Sold'} {o.symbol}</b><small className="muted block">{o.decidedOn}{o.fillPrice != null ? ` · at ${inr(o.fillPrice)}` : ''}</small></div></div>
              <span className={`badge ${o.status.toLowerCase()}`}>{o.status.toLowerCase()}</span>
            </div>
            {o.reason && <small className="muted">{o.reason}</small>}
          </div>
        ))}
      </Section>

      <Section title="My strategy" hint="Tell the AI how to trade">
        <textarea value={strategy} maxLength={500} rows={4} onChange={e => setStrategy(e.target.value)} aria-label="My strategy"
          placeholder="For example: only trade BTC and ETH, buy after a 5% dip, sell when up 8%, never hold more than 2 coins." />
        <button className="btn" disabled={busy} onClick={() => act(`${BASE}/strategy`, 'Strategy saved', { text: strategy })}>Save strategy</button>
        <small className="muted">The AI reads this at its next check and tries to follow it. It is not a strict rules engine, and the safety limits always apply.</small>
      </Section>

      <Section title="Account" hint="Add money or reset">
        <label className="field">
          <span>Add virtual cash</span>
          <div className="input-wrap">
            <b>₹</b>
            <input inputMode="decimal" value={extra} onChange={e => setExtra(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="5000" aria-label="Add virtual cash" />
            <button className="btn small-btn" disabled={busy || !extra} onClick={async () => { await act(`${BASE}/deposit`, 'Cash added', { amount: Number(extra) }); setExtra('') }}>Add</button>
          </div>
        </label>
        <small className="muted">Added cash counts as money you put in, so your return stays fair, but the Bitcoin comparison becomes approximate.</small>
        <button className="btn danger" disabled={busy} onClick={reset}>Reset paper account</button>
      </Section>

      <Section title="How it works">
        <ul className="points dark">
          <li>The AI sees prices, 1-hour, 24-hour and 7-day moves for 10 coins.</li>
          <li>Safety limits: at most 4 orders at a time, ₹200 minimum, and no coin above 35% of your portfolio.</li>
          <li>Costs: about 0.2% per trade plus 0.1% slippage. Prices come from CoinDCX.</li>
          <li>The AI is Google Gemini. Its free tier may use prompts to improve Google products, and they contain only prices and your virtual holdings.</li>
        </ul>
      </Section>
    </div>
  )
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [toast, setToast] = useState<ToastState>(null)
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const user: UserInfo = JSON.parse(localStorage.getItem('user') || '{}')

  const showToast = useCallback((t: NonNullable<ToastState>) => {
    clearTimeout(toastTimer.current)
    setToast(t)
    toastTimer.current = setTimeout(() => setToast(null), 5000)
  }, [])

  return (
    <>
      <header className="topbar">
        <div className="brand"><Logo /><span>AI Crypto Lab</span></div>
        <div className="user">
          {user.picture && <img src={user.picture} alt="" referrerPolicy="no-referrer" />}
          <span className="user-name">{user.name || user.email}</span>
          <button className="link" onClick={() => { clearSession(); onLogout() }}>Sign out</button>
        </div>
      </header>
      <main className="app">
        <Dashboard showToast={showToast} />
      </main>
      {toast && <div className="toast" role="status">{toast.msg}</div>}
    </>
  )
}

export default function App() {
  const [authed, setAuthed] = useState(!!localStorage.getItem('token'))
  useEffect(() => {
    if (!localStorage.getItem('token')) return
    api('/session/refresh', { method: 'POST' })
      .then(r => (r.ok ? r.json() : null))
      .then(d => { if (d?.token) localStorage.setItem('token', d.token) })
      .catch(() => { /* keep the current token if the server is waking up */ })
  }, [])
  const onLogin = useCallback(() => setAuthed(true), [])
  return authed ? <Shell onLogout={() => setAuthed(false)} /> : <Login onLogin={onLogin} />
}
