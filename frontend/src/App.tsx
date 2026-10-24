import { useCallback, useEffect, useRef, useState } from 'react'

type UserInfo = { name?: string; email?: string; picture?: string }
type ToastState = { msg: string } | null

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
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

const WAKING = 'Waking up the server. This can take up to a minute the first time...'

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
      <h1>AI Paper Trading</h1>
      <p className="muted">Practise trading with virtual money while an AI suggests the trades. Sign in or sign up with Google, no password needed.</p>
      <div ref={buttonRef} className="gbtn" />
      {(loading || busy) && <p className="muted">{slow ? WAKING : 'Loading...'}</p>}
      {error && <p className="error">{error}</p>}
    </main>
  )
}

const signed = (n: number) => `${n >= 0 ? '+' : '-'}${money(Math.abs(n))}`
const gainColor = (n: number | null) => (n == null ? undefined : n >= 0 ? '#16a34a' : 'var(--danger)')

type PaperView = {
  cash: number; startCash: number; startBenchmark: number | null; equity: number; returnPct: number; benchmarkPct: number | null
  positions: { symbol: string; qty: number; avgCost: number; price: number | null; value: number | null; pnl: number | null }[]
  orders: { id: number; symbol: string; side: string; qty: number; status: string; reason: string | null; decidedOn: string; fillPrice: number | null; fees: number | null; amount?: number | null }[]
  history: { day: string; equity: number; benchmark: number | null }[]
  lastSummary: string | null; lastDecisionOn: string | null; autoTrade: boolean; strategy: string | null
}

function Chart({ view, benchmarkName }: { view: PaperView; benchmarkName: string }) {
  const pts = view.history
  if (pts.length < 2) return <p className="muted center">The chart appears after two trading days.</p>
  const mine = pts.map(h => (h.equity / view.startCash) * 100)
  const bench = pts.map(h => (view.startBenchmark && h.benchmark ? (h.benchmark / view.startBenchmark) * 100 : NaN))
  const all = [...mine, ...bench.filter(v => !Number.isNaN(v)), 100]
  const lo = Math.min(...all)
  const hi = Math.max(...all)
  const w = 320
  const h = 100
  const x = (i: number) => (i / (pts.length - 1)) * w
  const y = (v: number) => h - ((v - lo) / (hi - lo || 1)) * h
  const line = (vals: number[]) => vals.map((v, i) => (Number.isNaN(v) ? '' : `${x(i).toFixed(1)},${y(v).toFixed(1)}`)).filter(Boolean).join(' ')
  return (
    <div>
      <svg viewBox={`0 0 ${w} ${h}`} width="100%" role="img" aria-label={`AI portfolio versus ${benchmarkName}`}>
        <polyline points={line(mine)} fill="none" stroke="#2563eb" strokeWidth="2" />
        <polyline points={line(bench)} fill="none" stroke="#9ca3af" strokeWidth="2" strokeDasharray="4 3" />
      </svg>
      <small className="muted">Blue: AI portfolio. Grey dashed: {benchmarkName}. Both start at 100.</small>
    </div>
  )
}

function PaperTrading({ showToast, market }: { showToast: (t: { msg: string }) => void; market: 'stock' | 'crypto' }) {
  const crypto = market === 'crypto'
  const base = crypto ? '/crypto-paper' : '/paper'
  const benchmarkName = crypto ? 'Bitcoin' : 'Nifty'
  const [status, setStatus] = useState<{ available: boolean; started: boolean } | null>(null)
  const [view, setView] = useState<PaperView | null>(null)
  const [busy, setBusy] = useState(false)
  const [amount, setAmount] = useState('10000')
  const [extra, setExtra] = useState('')
  const [strategy, setStrategy] = useState('')

  const load = useCallback(async () => {
    const st = await (await api(`${base}/status`)).json()
    setStatus(st)
    if (st.started) {
      const res = await api(base)
      if (res.ok) {
        const v: PaperView = await res.json()
        setView(v)
        setStrategy(v.strategy ?? '')
      }
    } else {
      setView(null)
    }
  }, [base])
  useEffect(() => { load().catch(() => setStatus({ available: false, started: false })) }, [load])

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
    if (!window.confirm('Reset paper trading? This deletes the virtual account and its history.')) return
    await act(`${base}/reset`, 'Paper account reset')
  }

  if (!status) return <p className="muted center">Loading...</p>
  if (!status.available) return <section className="card"><p className="muted">AI paper trading needs a free Gemini API key set on the server. It is not set up yet.</p></section>

  if (!status.started || !view) {
    return (
      <section className="card form">
        <strong>AI paper trading</strong>
        {crypto ? (
          <>
            <p className="muted">You choose a virtual amount in rupees. Crypto trades 24 hours a day, so every 4 hours an AI looks at prices of 10 popular coins and suggests simulated trades. Orders fill straight away at the current CoinDCX price with a small cost and slippage. No real money is ever used.</p>
            <p className="muted">This is a simulation for learning, not investment advice. Crypto is very volatile and AI results are unreliable. The point is to see honestly how it does against just holding Bitcoin. India&apos;s 1% TDS and crypto tax are not modelled. Prices come from CoinDCX and the AI is Google Gemini, whose free tier may use prompts to improve Google products.</p>
          </>
        ) : (
          <>
            <p className="muted">You choose a virtual amount to start with. Once a day after the market closes, an AI looks at recent prices of 14 large Indian stocks and a Nifty ETF and places simulated orders, which fill at the next day&apos;s opening price with realistic costs. No real money is ever used.</p>
            <p className="muted">This is a simulation for learning, not investment advice. AI results are unreliable, and the point is to see honestly how it does against just holding the Nifty. Market prices come from Yahoo and the AI is Google Gemini, whose free tier may use prompts to improve Google products.</p>
          </>
        )}
        <input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="Virtual amount in rupees" aria-label="Starting amount" />
        <small className="muted">Any amount from 1,000 to 1,00,00,000 rupees. You can add more later.</small>
        <button className="primary" disabled={busy || !amount} onClick={() => act(`${base}/start`, 'Paper account created', { amount: Number(amount) })}>{busy ? 'Starting...' : 'Start paper account'}</button>
      </section>
    )
  }

  const diff = view.benchmarkPct == null ? null : view.returnPct - view.benchmarkPct
  return (
    <>
      <section className="card summary">
        <div className="muted center">Paper portfolio value</div>
        <div className="grand">{money(view.equity)}</div>
        <div className="center">
          <strong style={{ color: gainColor(view.returnPct) }}>{view.returnPct >= 0 ? '+' : ''}{view.returnPct.toFixed(2)}%</strong>
          {view.benchmarkPct != null && <span> &middot; {benchmarkName} {view.benchmarkPct >= 0 ? '+' : ''}{view.benchmarkPct.toFixed(2)}%</span>}
          {diff != null && <span style={{ color: gainColor(diff) }}> &middot; {diff >= 0 ? 'ahead by' : 'behind by'} {Math.abs(diff).toFixed(2)} pts</span>}
        </div>
        <div className="center muted">Cash {money(view.cash)} &middot; started with {money(view.startCash)}</div>
        <Chart view={view} benchmarkName={benchmarkName} />
      </section>

      <section className="card setup">
        <strong>Latest AI view</strong>
        <p className="muted">{view.lastSummary || (crypto ? 'No decision yet. The AI decides every 4 hours, or you can run it now.' : 'No decision yet. The AI decides once a day after the market closes (about 4 PM IST), or you can run it now.')}</p>
        <div className="row">
          <button type="button" className="primary" disabled={busy} onClick={() => act(`${base}/run`)}>{busy ? 'Asking the AI...' : 'Run AI now'}</button>
          <button type="button" className="danger" disabled={busy} onClick={reset}>Reset</button>
        </div>
        <label className="row" style={{ alignItems: 'center' }}>
          <input type="checkbox" checked={view.autoTrade} disabled={busy} onChange={e => act(`${base}/auto`, e.target.checked ? 'Auto-trading on' : 'Auto-trading off', { enabled: e.target.checked })} style={{ minHeight: 'auto', width: 20, height: 20 }} />
          <span>Auto-trade: AI orders go through without asking me</span>
        </label>
        <small className="muted">{view.autoTrade ? (crypto ? 'Orders the AI decides fill automatically at the current price.' : 'Orders the AI decides fill automatically at the next trading day\'s open.') : 'With auto-trade off, the AI only suggests trades. You approve or decline each one.'}</small>
        <div className="row">
          <input inputMode="decimal" value={extra} onChange={e => setExtra(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="Add virtual cash" aria-label="Add virtual cash" style={{ flex: 1 }} />
          <button type="button" disabled={busy || !extra} onClick={async () => { await act(`${base}/deposit`, 'Cash added', { amount: Number(extra) }); setExtra('') }}>Add</button>
        </div>
        <small className="muted">Added cash counts as capital you put in, so return percentages stay fair, but comparing with {benchmarkName} becomes approximate.{crypto ? '' : ' The server must be awake at the next open for orders to fill, so this works best with the keep-awake monitor.'}</small>
      </section>

      <section className="card setup">
        <strong>My strategy (optional)</strong>
        <textarea value={strategy} maxLength={500} rows={3} onChange={e => setStrategy(e.target.value)} aria-label="My strategy"
          placeholder={crypto ? 'For example: only trade BTC and ETH, buy after a 5% dip, sell when up 8%, never hold more than 2 coins.' : 'For example: prefer banks and IT, buy on dips, sell when up 10%, keep at least 30% in cash.'}
          style={{ font: 'inherit', padding: '0.5rem 0.75rem', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--card)', color: 'inherit', resize: 'vertical' }} />
        <button type="button" disabled={busy} onClick={() => act(`${base}/strategy`, 'Strategy saved', { text: strategy })}>Save strategy</button>
        <small className="muted">The AI reads this in its next decision and tries to follow it, but the safety limits always apply. It is not a rules engine, so it may interpret it loosely.</small>
      </section>

      {view.orders.some(o => o.status === 'SUGGESTED') && <strong>AI suggestions for you</strong>}
      {view.orders.filter(o => o.status === 'SUGGESTED').map(o => (
        <div key={o.id} className="card form">
          <div className="kv">
            <strong>{o.side === 'BUY' ? 'Buy' : 'Sell'} {o.qty} {o.symbol.replace('.NS', '')}</strong>
            <small className="muted">{o.amount != null ? `about ${money(o.amount)} INR` : o.decidedOn}</small>
          </div>
          {o.reason && <small className="muted">{o.reason}</small>}
          <div className="row">
            <button type="button" className="primary" disabled={busy} onClick={() => act(`${base}/orders/${o.id}/approve`, crypto ? 'Approved and filled at the current price' : 'Approved. It fills at the next open.')}>Approve</button>
            <button type="button" disabled={busy} onClick={() => act(`${base}/orders/${o.id}/decline`, 'Declined')}>Decline</button>
          </div>
        </div>
      ))}

      {view.positions.map(p => (
        <div key={p.symbol} className="card item">
          <span className="desc"><span>{p.symbol.replace('.NS', '')}</span><small>{p.qty} shares &middot; avg {money(p.avgCost)}</small></span>
          <span className="right">
            <strong>{p.value != null ? money(p.value) : 'n/a'}</strong>
            {p.pnl != null && <small style={{ color: gainColor(p.pnl) }}>{signed(p.pnl)}</small>}
          </span>
        </div>
      ))}
      {view.positions.length === 0 && <p className="muted center">No open positions.</p>}

      {view.orders.length > 0 && <strong>Recent orders</strong>}
      {view.orders.filter(o => o.status !== 'SUGGESTED').map(o => (
        <div key={o.id} className="card">
          <div className="kv">
            <strong>{o.side} {o.qty} {o.symbol.replace('.NS', '')}</strong>
            <small className="muted">{o.status.toLowerCase()}{o.fillPrice != null ? ` at ${money(o.fillPrice)}` : ''} &middot; {o.decidedOn}</small>
          </div>
          {o.reason && <small className="muted">{o.reason}</small>}
        </div>
      ))}
    </>
  )
}

function Tracker({ onLogout }: { onLogout: () => void }) {
  const [market, setMarket] = useState<'stock' | 'crypto'>('stock')
  const [toast, setToast] = useState<ToastState>(null)
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const user: UserInfo = JSON.parse(localStorage.getItem('user') || '{}')

  const showToast = useCallback((t: NonNullable<ToastState>) => {
    clearTimeout(toastTimer.current)
    setToast(t)
    toastTimer.current = setTimeout(() => setToast(null), 6000)
  }, [])

  return (
    <main className="app">
      <header className="top">
        <div className="who">
          {user.picture && <img src={user.picture} alt="" referrerPolicy="no-referrer" />}
          <span>{user.name || user.email}</span>
        </div>
        <button className="link" onClick={() => { clearSession(); onLogout() }}>Sign out</button>
      </header>

      <nav className="tabs">
        <button className={market === 'stock' ? 'on' : ''} onClick={() => setMarket('stock')}>Stocks</button>
        <button className={market === 'crypto' ? 'on' : ''} onClick={() => setMarket('crypto')}>Crypto 24/7</button>
      </nav>

      <PaperTrading key={market} market={market} showToast={showToast} />

      {toast && (
        <div className="toast" role="status">
          <span>{toast.msg}</span>
        </div>
      )}
    </main>
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
  return authed ? <Tracker onLogout={() => setAuthed(false)} /> : <Login onLogin={onLogin} />
}
