import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'

type UserInfo = { name?: string; email?: string; picture?: string }
type ToastState = { msg: string } | null

type Order = {
  id: number; symbol: string; side: string; qty: number; status: string; reason: string | null
  decidedOn: string; fillPrice: number | null; fees: number | null; amount?: number | null
  triggerPrice?: number | null; triggerDir?: string | null; source?: string | null
}
type Analysis = {
  performance: { pnl: number; returnPct: number; benchmarkPct: number | null; alphaPts: number | null; bestDay: string | null; bestDayPct: number | null; worstDay: string | null; worstDayPct: number | null; maxDrawdownPct: number; daysTracked: number }
  cashPct: number; coinsPct: number
  coins: { symbol: string; qty: number; price: number | null; value: number; sharePct: number; avgCost: number | null; unrealized: number; realized: number; fees: number; trades: number }[]
  trades: { trades: number; buys: number; sells: number; fees: number; realized: number; winRatePct: number | null; avgWin: number | null; avgLoss: number | null; bestTrade: number | null; worstTrade: number | null }
  ai: { executed: number; declined: number; cancelled: number; acceptancePct: number | null; bySource: { source: string; filled: number; sells: number; realized: number }[] }
}
type PaperView = {
  cash: number; startCash: number; startBenchmark: number | null; equity: number; returnPct: number; benchmarkPct: number | null
  positions: { symbol: string; qty: number; avgCost: number; price: number | null; value: number | null; pnl: number | null; side: string }[]
  orders: Order[]
  history: { day: string; equity: number; benchmark: number | null }[]
  lastSummary: string | null; lastDecisionOn: string | null; autoTrade: boolean; strategy: string | null
  notifyEmail: boolean; emailConfigured: boolean
}

type LiveView = {
  equity: number; cash: number; returnPct: number; benchmarkPct: number | null; benchmarkPrice: number | null
  positions: { symbol: string; price: number | null; value: number | null; pnl: number | null }[]
  prices: Record<string, number>
  asOf: string
}

const BASE = '/crypto-paper'
const WAKING = 'Waking up the server. This can take up to a minute the first time...'
const PALETTE = ['#f7931a', '#627eea', '#f3ba2f', '#3b82f6', '#14b8a6', '#c2a633', '#8b5cf6', '#ef4444', '#ec4899', '#2a5ada']

const inr = (n: number) => n.toLocaleString('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 })
const qtyText = (n: number) => n.toLocaleString('en-IN', { maximumFractionDigits: 8 })
const pctText = (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
const COINS = ['BTC', 'ETH', 'BNB', 'XRP', 'SOL', 'DOGE', 'ADA', 'AVAX', 'DOT', 'LINK']
const signedInr = (n: number) => `${n >= 0 ? '+' : '-'}${inr(Math.abs(n))}`
const tone = (n: number | null | undefined): 'up' | 'down' | undefined => (n == null || n === 0 ? undefined : n > 0 ? 'up' : 'down')
const coinColor = (symbol: string) => PALETTE[[...symbol].reduce((a, c) => a + c.charCodeAt(0), 0) % PALETTE.length]

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

function useFlash(value: number) {
  const prev = useRef(value)
  const [dir, setDir] = useState<'up' | 'down' | ''>('')
  useEffect(() => {
    if (value === prev.current) return
    setDir(value > prev.current ? 'up' : 'down')
    prev.current = value
    const t = setTimeout(() => setDir(''), 800)
    return () => clearTimeout(t)
  }, [value])
  return dir
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
  return <span className="coin" style={{ background: coinColor(symbol) }}>{symbol.slice(0, 3)}</span>
}

function Stat({ label, value, tone: t }: { label: string; value: string; tone?: 'up' | 'down' }) {
  return <div className="stat"><small>{label}</small><b className={t ?? ''}>{value}</b></div>
}

function Chart({ view, live }: { view: PaperView; live: LiveView | null }) {
  const pts = [
    { equity: view.startCash, benchmark: view.startBenchmark },
    ...view.history,
    ...(live ? [{ equity: live.equity, benchmark: live.benchmarkPrice }] : []),
  ]
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
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [tCoin, setTCoin] = useState('BTC')
  const [tSide, setTSide] = useState<'BUY' | 'SELL' | 'SHORT' | 'COVER'>('BUY')
  const [tMode, setTMode] = useState<'now' | 'price'>('now')
  const [tAmount, setTAmount] = useState('')
  const [tPercent, setTPercent] = useState(100)
  const [tPrice, setTPrice] = useState('')
  const slow = useSlow(status === null)
  const [live, setLive] = useState<LiveView | null>(null)
  const [liveOk, setLiveOk] = useState(true)
  const started = !!status?.started

  useEffect(() => {
    if (!started) return
    let stop = false
    let inflight = false
    let fails = 0
    const tick = async () => {
      if (document.visibilityState !== 'visible' || inflight) return
      inflight = true
      try {
        const res = await fetch(`/api${BASE}/live`, { headers: { Authorization: `Bearer ${localStorage.getItem('token') ?? ''}` } })
        if (!res.ok) throw new Error(String(res.status))
        const l: LiveView = await res.json()
        if (!stop) {
          setLive(l)
          setLiveOk(true)
        }
        fails = 0
      } catch {
        fails++
        if (fails >= 3 && !stop) setLiveOk(false)
      } finally {
        inflight = false
      }
    }
    tick()
    const t = setInterval(tick, 3000)
    document.addEventListener('visibilitychange', tick)
    return () => { stop = true; clearInterval(t); document.removeEventListener('visibilitychange', tick) }
  }, [started])

  const liveEquity = live?.equity ?? view?.equity ?? 0
  const flash = useFlash(liveEquity)

  const load = useCallback(async () => {
    const st = await (await api(`${BASE}/status`)).json()
    setStatus(st)
    if (st.started) {
      const [res, an] = await Promise.all([api(BASE), api(`${BASE}/analysis`)])
      if (res.ok) {
        const v: PaperView = await res.json()
        setView(v)
        setStrategy(s => (s === '' ? v.strategy ?? '' : s))
      }
      if (an.ok) setAnalysis(await an.json())
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
        const body = await res.json().catch(() => ({}))
        const msg = res.status === 400 ? body.message || 'Please check the values and try again.' : 'Something went wrong. Please try again.'
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
  const watchingOrders = view.orders.filter(o => o.status === 'WATCHING')
  const history = view.orders.filter(o => o.status !== 'SUGGESTED' && o.status !== 'WATCHING')
  const prices = live?.prices ?? {}
  const tPriceNow = prices[tCoin] ?? null
  const tHeld = view.positions.find(p => p.symbol === tCoin && (p.side === 'SHORT') === (tSide === 'COVER'))
  const tOpens = tSide === 'BUY' || tSide === 'SHORT'
  const sideWord = (s: string) => ({ BUY: 'Buy', SELL: 'Sell', SHORT: 'Short', COVER: 'Cover' } as Record<string, string>)[s] ?? s
  const sidePast = (s: string) => ({ BUY: 'Bought', SELL: 'Sold', SHORT: 'Shorted', COVER: 'Covered' } as Record<string, string>)[s] ?? s
  const sideClass = (s: string) => (s === 'BUY' || s === 'COVER' ? 'buy' : 'sell')
  const submitTrade = async () => {
    const body: Record<string, unknown> = { side: tSide, symbol: tCoin }
    if (tOpens) body.amount = Number(tAmount)
    else body.percent = tPercent
    if (tMode === 'price') body.price = Number(tPrice)
    await act(`${BASE}/${tMode === 'price' ? 'trigger' : 'trade'}`, undefined, body)
    setTAmount('')
    setTPrice('')
  }
  const equity = live?.equity ?? view.equity
  const cash = live?.cash ?? view.cash
  const returnPct = live?.returnPct ?? view.returnPct
  const benchmarkPct = live ? live.benchmarkPct : view.benchmarkPct
  const diff = benchmarkPct == null ? null : returnPct - benchmarkPct
  const holdingsValue = equity - cash
  const up = returnPct >= 0
  const liveBySymbol = new Map((live?.positions ?? []).map(p => [p.symbol, p]))
  const asOf = live ? new Date(live.asOf).toLocaleTimeString() : null

  return (
    <div className="stack">
      <section className="hero">
        <div className="hero-top">
          <span className="row gap-s">Portfolio value <span className={`live ${liveOk ? '' : 'off'}`}><i />{liveOk ? 'LIVE' : 'Reconnecting'}</span></span>
          <span className={`pill ${up ? 'up' : 'down'}`}>{up ? '▲' : '▼'} {pctText(returnPct)}</span>
        </div>
        <div className={`hero-value flash-${flash}`}>{inr(equity)}</div>
        <div className="hero-sub">
          {benchmarkPct != null && <span>Bitcoin {pctText(benchmarkPct)}</span>}
          {diff != null && <span className="chip-soft">{diff >= 0 ? 'Beating Bitcoin by' : 'Behind Bitcoin by'} {Math.abs(diff).toFixed(2)} pts</span>}
          {asOf && <span className="updated">Updated {asOf}</span>}
        </div>
        <Chart view={view} live={live} />
      </section>

      <Section title="Performance analysis" hint="Return, risk, best and worst day">
        {analysis ? (
          <>
            <div className="stats">
              <Stat label="Profit / loss" value={signedInr(analysis.performance.pnl)} tone={tone(analysis.performance.pnl)} />
              <Stat label="Return" value={pctText(analysis.performance.returnPct)} tone={tone(analysis.performance.returnPct)} />
              <Stat label="Bitcoin return" value={analysis.performance.benchmarkPct == null ? 'n/a' : pctText(analysis.performance.benchmarkPct)} />
              <Stat label="Versus Bitcoin" value={analysis.performance.alphaPts == null ? 'n/a' : `${analysis.performance.alphaPts >= 0 ? '+' : ''}${analysis.performance.alphaPts.toFixed(2)} pts`} tone={tone(analysis.performance.alphaPts)} />
              <Stat label="Biggest fall" value={`-${analysis.performance.maxDrawdownPct.toFixed(2)}%`} />
              <Stat label="Days tracked" value={String(analysis.performance.daysTracked)} />
              <Stat label="Best day" value={analysis.performance.bestDay ? `${pctText(analysis.performance.bestDayPct ?? 0)} (${analysis.performance.bestDay})` : 'n/a'} />
              <Stat label="Worst day" value={analysis.performance.worstDay ? `${pctText(analysis.performance.worstDayPct ?? 0)} (${analysis.performance.worstDay})` : 'n/a'} />
            </div>
            <small className="muted">Biggest fall is the largest drop from a peak your portfolio value reached. Best and worst day use the daily snapshots, so they fill in as days pass.</small>
          </>
        ) : <p className="muted small">Collecting data...</p>}
      </Section>

      <div className="tiles">
        <div className="tile"><small>Cash</small><b>{inr(cash)}</b></div>
        <div className="tile"><small>In coins</small><b>{inr(holdingsValue)}</b></div>
        <div className="tile"><small>Started with</small><b>{inr(view.startCash)}</b></div>
      </div>

      <Section title="Allocation analysis" hint="Where your money sits">
        {analysis ? (
          <>
            <div className="alloc" role="img" aria-label="Portfolio allocation">
              <span className="seg cash" style={{ width: `${analysis.cashPct}%` }} />
              {analysis.coins.filter(c => c.sharePct > 0).map(c => <span key={c.symbol} className="seg" style={{ width: `${c.sharePct}%`, background: coinColor(c.symbol) }} />)}
            </div>
            <div className="legend-list">
              <div><i className="dot cash" /> Cash <b>{analysis.cashPct.toFixed(1)}%</b></div>
              {analysis.coins.filter(c => c.sharePct > 0).map(c => <div key={c.symbol}><i className="dot" style={{ background: coinColor(c.symbol) }} /> {c.symbol} <b>{c.sharePct.toFixed(1)}%</b></div>)}
            </div>
            <small className="muted">
              {analysis.coins.find(c => c.sharePct > 0)
                ? `Your biggest position is ${analysis.coins.find(c => c.sharePct > 0)?.symbol} at ${analysis.coins.find(c => c.sharePct > 0)?.sharePct.toFixed(1)}% of the portfolio. The AI never puts more than 35% into one coin.`
                : 'You are fully in cash right now.'}
            </small>
          </>
        ) : <p className="muted small">Collecting data...</p>}
      </Section>

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

      <Section title="AI performance" hint="How its trades have gone">
        {analysis ? (
          <>
            <div className="stats">
              <Stat label="AI trades done" value={String(analysis.ai.executed)} />
              <Stat label="You declined" value={String(analysis.ai.declined)} />
              <Stat label="Expired or replaced" value={String(analysis.ai.cancelled)} />
              <Stat label="You accepted" value={analysis.ai.acceptancePct == null ? 'n/a' : `${analysis.ai.acceptancePct.toFixed(0)}%`} />
              {analysis.ai.bySource.map(src => (
                <Stat key={src.source} label={`${src.source === 'USER' ? 'Your' : 'AI'} realised profit`} value={signedInr(src.realized)} tone={tone(src.realized)} />
              ))}
            </div>
            <small className="muted">Realised profit counts only trades you have closed by selling, after fees. Profit on coins you still hold is under Coin analysis.</small>
          </>
        ) : <p className="muted small">Collecting data...</p>}
      </Section>

      {suggestions.length > 0 && (
        <section className="stack">
          <h3 className="section-title">Waiting for your decision <span className="count">{suggestions.length}</span></h3>
          {suggestions.map(o => (
            <div key={o.id} className="card suggestion">
              <div className="row between">
                <div className="row gap"><Coin symbol={o.symbol} /><div><b>{sideWord(o.side)} {o.symbol}</b><small className="muted block">{qtyText(o.qty)} coins{o.amount != null ? `, about ${inr(o.amount)}` : ''}</small></div></div>
                <span className={`badge ${sideClass(o.side)}`}>{o.side}</span>
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

      <Section title="Your own trade" hint="Custom entry or exit" defaultOpen>
        <div className="chips">
          <button type="button" className={`chip ${tSide === 'BUY' ? 'on' : ''}`} onClick={() => setTSide('BUY')}>Buy (entry)</button>
          <button type="button" className={`chip ${tSide === 'SELL' ? 'on' : ''}`} onClick={() => setTSide('SELL')}>Sell (exit)</button>
          <button type="button" className={`chip ${tSide === 'SHORT' ? 'on' : ''}`} onClick={() => setTSide('SHORT')}>Short (bet on fall)</button>
          <button type="button" className={`chip ${tSide === 'COVER' ? 'on' : ''}`} onClick={() => setTSide('COVER')}>Cover (close short)</button>
          <span className="spacer" />
          <button type="button" className={`chip ${tMode === 'now' ? 'on' : ''}`} onClick={() => setTMode('now')}>Now</button>
          <button type="button" className={`chip ${tMode === 'price' ? 'on' : ''}`} onClick={() => setTMode('price')}>At my price</button>
        </div>
        <label className="field">
          <span>Coin</span>
          <select value={tCoin} onChange={e => setTCoin(e.target.value)} aria-label="Coin">
            {COINS.map(c => <option key={c} value={c}>{c}{prices[c] != null ? `  ·  ${inr(prices[c])}` : ''}</option>)}
          </select>
        </label>
        {tSide === 'SHORT' && <small className="muted">A short earns when the price falls and loses when it rises. Your cash is set aside as backing (no leverage) and the short closes automatically if it loses 90% of it.</small>}
        {tOpens ? (
          <label className="field">
            <span>{tSide === 'SHORT' ? 'Amount to short' : 'Amount to spend'}</span>
            <div className="input-wrap">
              <b>₹</b>
              <input inputMode="decimal" value={tAmount} onChange={e => setTAmount(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="1000" aria-label="Amount to spend" />
            </div>
            <div className="chips">
              {[500, 1000, 2500].map(v => <button key={v} type="button" className="chip" onClick={() => setTAmount(String(v))}>{inr(v).replace('.00', '')}</button>)}
              <button type="button" className="chip" onClick={() => setTAmount(String(Math.floor(cash / 1.002)))}>All cash</button>
            </div>
          </label>
        ) : (
          <div className="field">
            <span>How much of your {tSide === 'COVER' ? `${tCoin} short to cover` : `${tCoin} to sell`} {tHeld ? `(you ${tSide === 'COVER' ? 'are short' : 'hold'} ${qtyText(tHeld.qty)})` : tSide === 'COVER' ? '(no short open)' : '(you hold none)'}</span>
            <div className="chips">
              {[25, 50, 75, 100].map(v => <button key={v} type="button" className={`chip ${tPercent === v ? 'on' : ''}`} onClick={() => setTPercent(v)}>{v}%</button>)}
            </div>
          </div>
        )}
        {tMode === 'price' && (
          <label className="field">
            <span>Trigger price {tPriceNow != null && <small className="muted">(now {inr(tPriceNow)})</small>}</span>
            <div className="input-wrap">
              <b>₹</b>
              <input inputMode="decimal" value={tPrice} onChange={e => setTPrice(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="Price to wait for" aria-label="Trigger price" />
            </div>
            <small className="muted">
              {tPrice && tPriceNow != null
                ? `${sideWord(tSide)}s when ${tCoin} ${Number(tPrice) > tPriceNow ? 'rises to' : 'falls to'} ${inr(Number(tPrice))}${tSide === 'SELL' ? (Number(tPrice) > tPriceNow ? ' (take profit)' : ' (stop loss)') : tSide === 'COVER' ? (Number(tPrice) < tPriceNow ? ' (take profit on the short)' : ' (stop loss on the short)') : tSide === 'SHORT' ? (Number(tPrice) > tPriceNow ? ' (short at a bounce)' : ' (short on a breakdown)') : (Number(tPrice) < tPriceNow ? ' (buy the dip)' : ' (breakout entry)')}.`
                : 'Pick a price above the current price to wait for a rise, or below it to wait for a fall.'}
            </small>
          </label>
        )}
        <button className="btn primary" disabled={busy || (tOpens && !tAmount) || (tMode === 'price' && !tPrice)} onClick={submitTrade}>
          {tMode === 'now' ? (tOpens ? `${sideWord(tSide)} ${tCoin} now` : `${sideWord(tSide)} ${tPercent}% of ${tCoin} now`) : `Watch ${tCoin} and ${sideWord(tSide).toLowerCase()}`}
        </button>
        <small className="muted">Orders at the live price fill instantly with a small cost and slippage. Price orders are checked every few seconds while the server is awake, and need the cash or coins to be there when they trigger.</small>
      </Section>

      {watchingOrders.length > 0 && (
        <section className="stack">
          <h3 className="section-title">Waiting for your price <span className="count">{watchingOrders.length}</span></h3>
          {watchingOrders.map(o => (
            <div key={o.id} className="card suggestion">
              <div className="row between">
                <div className="row gap"><Coin symbol={o.symbol} /><div><b>{sideWord(o.side)} {o.symbol}</b><small className="muted block">{o.side === 'BUY' || o.side === 'SHORT' ? `about ${inr(o.amount ?? 0)}` : `${qtyText(o.qty)} coins`} when price {o.triggerDir === 'ABOVE' ? 'rises to' : 'falls to'} {o.triggerPrice != null ? inr(o.triggerPrice) : ''}</small></div></div>
                <span className="badge suggested">watching</span>
              </div>
              {prices[o.symbol] != null && <small className="muted">Now {inr(prices[o.symbol])}</small>}
              <button className="btn" disabled={busy} onClick={() => act(`${BASE}/orders/${o.id}/cancel`, 'Order cancelled')}>Cancel order</button>
            </div>
          ))}
        </section>
      )}

      <section className="stack">
        <h3 className="section-title">Your coins</h3>
        {view.positions.length === 0 && <div className="card empty"><b>No coins yet</b><small className="muted">Approve a suggestion, or turn on auto-trade, and your holdings show up here.</small></div>}
        {view.positions.map(p => {
          const lp = liveBySymbol.get(p.symbol)
          const price = lp?.price ?? p.price
          const value = lp?.value ?? p.value
          const pnl = lp?.pnl ?? p.pnl
          return (
            <div key={p.symbol} className="card holding">
              <div className="row gap"><Coin symbol={p.symbol} /><div><b>{p.symbol}{p.side === 'SHORT' && <span className="badge sell short-tag">SHORT</span>}</b><small className="muted block">{qtyText(p.qty)}{p.side === 'SHORT' ? ` · entry ${inr(p.avgCost)}` : ''} · now {price != null ? inr(price) : 'n/a'}</small></div></div>
              <div className="right">
                <b>{value != null ? inr(value) : 'n/a'}</b>
                {pnl != null && <span className={`pnl ${pnl >= 0 ? 'up' : 'down'}`}>{pnl >= 0 ? '+' : '-'}{inr(Math.abs(pnl))}</span>}
              </div>
            </div>
          )
        })}
      </section>

      <Section title="Coin analysis" hint="Share, profit and trades per coin">
        {analysis && analysis.coins.length > 0 ? analysis.coins.map(c => (
          <div key={c.symbol} className="activity">
            <div className="row between">
              <div className="row gap"><Coin symbol={c.symbol} /><b>{c.symbol}</b></div>
              <span className="muted small">{c.sharePct.toFixed(1)}% of portfolio</span>
            </div>
            <div className="stats three">
              <Stat label="Unrealised" value={signedInr(c.unrealized)} tone={tone(c.unrealized)} />
              <Stat label="Realised" value={signedInr(c.realized)} tone={tone(c.realized)} />
              <Stat label="Fees paid" value={inr(c.fees)} />
            </div>
            <small className="muted">{c.trades} trades{c.avgCost != null ? ` · average cost ${inr(c.avgCost)}` : ''}</small>
          </div>
        )) : <p className="muted small">{analysis ? 'No coins traded yet.' : 'Collecting data...'}</p>}
      </Section>

      <Section title="Recent activity" hint={history.length ? `${history.length} trades` : undefined}>
        {history.length === 0 && <p className="muted small">Nothing yet.</p>}
        {history.map(o => (
          <div key={o.id} className="activity">
            <div className="row between">
              <div className="row gap"><Coin symbol={o.symbol} /><div><b>{sidePast(o.side)} {o.symbol}</b><small className="muted block">{o.source === 'USER' ? 'You' : o.source === 'SYSTEM' ? 'Auto-close' : 'AI'} · {o.decidedOn}{o.fillPrice != null ? ` · at ${inr(o.fillPrice)}` : ''}</small></div></div>
              <span className={`badge ${o.status.toLowerCase()}`}>{o.status.toLowerCase()}</span>
            </div>
            {o.reason && <small className="muted">{o.reason}</small>}
          </div>
        ))}
      </Section>

      <Section title="Trade statistics" hint="Fees, wins and losses">
        {analysis ? (
          <>
            <div className="stats">
              <Stat label="Trades" value={`${analysis.trades.trades} (${analysis.trades.buys} buys, ${analysis.trades.sells} sells)`} />
              <Stat label="Fees paid" value={inr(analysis.trades.fees)} />
              <Stat label="Realised profit" value={signedInr(analysis.trades.realized)} tone={tone(analysis.trades.realized)} />
              <Stat label="Win rate" value={analysis.trades.winRatePct == null ? 'n/a' : `${analysis.trades.winRatePct.toFixed(0)}%`} />
              <Stat label="Average win" value={analysis.trades.avgWin == null ? 'n/a' : signedInr(analysis.trades.avgWin)} tone={tone(analysis.trades.avgWin)} />
              <Stat label="Average loss" value={analysis.trades.avgLoss == null ? 'n/a' : signedInr(analysis.trades.avgLoss)} tone={tone(analysis.trades.avgLoss)} />
              <Stat label="Best sell" value={analysis.trades.bestTrade == null ? 'n/a' : signedInr(analysis.trades.bestTrade)} tone={tone(analysis.trades.bestTrade)} />
              <Stat label="Worst sell" value={analysis.trades.worstTrade == null ? 'n/a' : signedInr(analysis.trades.worstTrade)} tone={tone(analysis.trades.worstTrade)} />
            </div>
            <small className="muted">Win rate and averages only count sells, because a trade is only a win or a loss once you close it.</small>
          </>
        ) : <p className="muted small">Collecting data...</p>}
      </Section>

      <Section title="My strategy" hint="Tell the AI how to trade">
        <textarea value={strategy} maxLength={500} rows={4} onChange={e => setStrategy(e.target.value)} aria-label="My strategy"
          placeholder="For example: only trade BTC and ETH, buy after a 5% dip, sell when up 8%, never hold more than 2 coins." />
        <button className="btn" disabled={busy} onClick={() => act(`${BASE}/strategy`, 'Strategy saved', { text: strategy })}>Save strategy</button>
        <small className="muted">The AI reads this at its next check and tries to follow it. It is not a strict rules engine, and the safety limits always apply.</small>
      </Section>

      <Section title="Email alerts" hint={view.emailConfigured ? (view.notifyEmail ? 'On' : 'Off') : 'Not set up'}>
        {view.emailConfigured ? (
          <>
            <label className="switch">
              <input type="checkbox" checked={view.notifyEmail} disabled={busy} aria-label="Email me about trades"
                onChange={e => act(`${BASE}/notify`, e.target.checked ? 'Email alerts on' : 'Email alerts off', { enabled: e.target.checked })} />
              <span className="track"><span className="knob" /></span>
              <span className="switch-label">Email me about trades</span>
            </label>
            <small className="muted">You get an email when the AI trades or suggests a trade, when one of your price orders triggers, and when you place a trade yourself. Emails go to the Google address you signed in with. Check your spam folder the first time.</small>
            <button className="btn" disabled={busy} onClick={() => act(`${BASE}/notify/test`)}>Send a test email</button>
          </>
        ) : <p className="muted small">Email alerts are not set up on the server yet.</p>}
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
