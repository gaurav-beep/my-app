import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'

type Expense = { id: number; description: string; amount: number; category: string; spentOn: string; source?: string }
type Total = { category: string; total: number }
type UserInfo = { name?: string; email?: string; picture?: string }
type Values = { amount: string; description: string; category: string; spentOn: string }
type ToastState = { msg: string; undo?: () => void } | null

const COLORS: Record<string, string> = {
  Food: '#f97316', Transport: '#3b82f6', Bills: '#8b5cf6', Shopping: '#ec4899',
  Health: '#10b981', Entertainment: '#eab308', Other: '#64748b',
}
const CATEGORIES = Object.keys(COLORS)
const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
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
      <h1>Expense Tracker</h1>
      <p className="muted">Track what you spend in seconds. Sign in or sign up with Google, no password needed.</p>
      <div ref={buttonRef} className="gbtn" />
      {(loading || busy) && <p className="muted">{slow ? WAKING : 'Loading...'}</p>}
      {error && <p className="error">{error}</p>}
    </main>
  )
}

function EntryForm({ initial, label, focus, onSubmit, onCancel, onDelete }: {
  initial: Values
  label: string
  focus?: boolean
  onSubmit: (v: Values) => Promise<boolean>
  onCancel?: () => void
  onDelete?: () => void
}) {
  const [v, setV] = useState(initial)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const amountRef = useRef<HTMLInputElement>(null)
  useEffect(() => { if (focus) amountRef.current?.focus() }, [focus])
  const set = (k: keyof Values, val: string) => setV(p => ({ ...p, [k]: val }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const n = Number(v.amount.replace(',', '.'))
    if (!(n > 0)) return setErr('Enter an amount')
    setBusy(true)
    setErr('')
    const ok = await onSubmit({ ...v, amount: String(n) }).catch(() => false)
    setBusy(false)
    if (!ok) return setErr('Could not save. Please try again.')
    if (!onCancel) {
      setV(p => ({ ...p, amount: '', description: '' }))
      amountRef.current?.focus()
    }
  }

  return (
    <form onSubmit={submit} className="card form">
      <input ref={amountRef} className="amount" inputMode="decimal" placeholder="0.00" aria-label="Amount"
        value={v.amount} onChange={e => set('amount', e.target.value)} />
      <input placeholder="What was it for? (optional)" aria-label="Description"
        value={v.description} onChange={e => set('description', e.target.value)} />
      <div className="chips" role="radiogroup" aria-label="Category">
        {CATEGORIES.map(c => (
          <button type="button" key={c} role="radio" aria-checked={v.category === c}
            className={`chip${v.category === c ? ' on' : ''}`} style={{ ['--c' as string]: COLORS[c] }}
            onClick={() => set('category', c)}>{c}</button>
        ))}
      </div>
      <input type="date" aria-label="Date" value={v.spentOn} max={today()} onChange={e => set('spentOn', e.target.value || today())} />
      {err && <p className="error">{err}</p>}
      <div className="row">
        <button className="primary" disabled={busy}>{busy ? 'Saving...' : label}</button>
        {onCancel && <button type="button" onClick={onCancel}>Cancel</button>}
        {onDelete && <button type="button" className="danger" onClick={onDelete}>Delete</button>}
      </div>
    </form>
  )
}

function SmsSetup() {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [token, setToken] = useState('')
  const [copied, setCopied] = useState('')
  const url = `${window.location.origin}/api/ingest/sms`

  useEffect(() => {
    api('/ingest/status').then(r => r.json()).then(d => setEnabled(d.enabled)).catch(() => setEnabled(false))
  }, [])

  const create = async () => {
    const r = await api('/ingest/token', { method: 'POST' })
    if (r.ok) {
      setToken((await r.json()).token)
      setEnabled(true)
    }
  }
  const revoke = async () => {
    await api('/ingest/token', { method: 'DELETE' })
    setToken('')
    setEnabled(false)
  }
  const copy = async (label: string, text: string) => {
    await navigator.clipboard.writeText(text)
    setCopied(label)
    setTimeout(() => setCopied(''), 1500)
  }

  return (
    <section className="card setup">
      <strong>Auto-add expenses from bank SMS (Android)</strong>
      <p className="muted">
        Your phone forwards each bank debit SMS to this app, and it appears here within seconds. Credits, OTPs and failed
        payments are ignored. Duplicates are skipped.
      </p>
      <ol>
        <li>Install the free <b>MacroDroid</b> app and allow SMS access.</li>
        <li>New macro, trigger: <b>SMS Received</b> (any number, or just your bank), content contains <b>debited</b>, <b>spent</b> or <b>paid</b>.</li>
        <li>Action: <b>HTTP Request</b>, method <b>POST</b>, content type <b>text/plain</b>, body <code>{'{sms_message}'}</code>.</li>
        <li>Use the URL and add the header <code>X-Ingest-Token</code> with your key, both shown below.</li>
      </ol>
      <div className="kv"><code>{url}</code><button type="button" onClick={() => copy('url', url)}>{copied === 'url' ? 'Copied' : 'Copy URL'}</button></div>
      {token ? (
        <>
          <div className="kv"><code>{token}</code><button type="button" onClick={() => copy('key', token)}>{copied === 'key' ? 'Copied' : 'Copy key'}</button></div>
          <p className="muted">Save this key now. It is shown only once.</p>
        </>
      ) : (
        <p className="muted">{enabled === null ? 'Checking...' : enabled ? 'A key is active. Generate a new one to replace it.' : 'No key yet.'}</p>
      )}
      <div className="row">
        <button type="button" className="primary" onClick={create}>{enabled ? 'Generate new key' : 'Generate key'}</button>
        {enabled && <button type="button" className="danger" onClick={revoke}>Turn off</button>}
      </div>
    </section>
  )
}

type HoldingRow = {
  id: number; type: 'mf' | 'stock' | 'crypto' | 'coindcx' | 'kitemf' | 'cas'; source?: string; symbol: string; name: string; units: number; invested: number | null
  price: number | null; value: number | null; gain: number | null; gainPct: number | null
}
type Portfolio = { holdings: HoldingRow[]; totalInvested: number; totalValue: number; totalGain: number; unknownCost: number; complete: boolean }

const signed = (n: number) => `${n >= 0 ? '+' : '-'}${money(Math.abs(n))}`
const gainColor = (n: number | null) => (n == null ? undefined : n >= 0 ? '#16a34a' : 'var(--danger)')

function CoinDcxCard({ onChange, showToast }: { onChange: () => void; showToast: (t: { msg: string }) => void }) {
  const [status, setStatus] = useState<{ available: boolean; linked: boolean } | null>(null)
  const [open, setOpen] = useState(false)
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api('/coindcx/status').then(r => r.json()).then(setStatus).catch(() => setStatus({ available: false, linked: false }))
  }, [])

  const run = async (path: string, init: RequestInit, ok: string) => {
    setBusy(true)
    try {
      const res = await api(path, init)
      if (!res.ok) {
        const msg = res.status === 502 ? 'CoinDCX rejected the key. Check it and its permissions.' : res.status === 503 ? 'Not set up on the server yet.' : 'Something went wrong. Please try again.'
        showToast({ msg })
        return false
      }
      showToast({ msg: ok })
      return true
    } finally {
      setBusy(false)
    }
  }

  const connect = async (e: FormEvent) => {
    e.preventDefault()
    if (await run('/coindcx/key', { method: 'PUT', body: JSON.stringify({ apiKey, apiSecret }) }, 'CoinDCX connected')) {
      setApiKey('')
      setApiSecret('')
      setOpen(false)
      setStatus(s => s && { ...s, linked: true })
      onChange()
    }
  }
  const sync = async () => { if (await run('/coindcx/sync', { method: 'POST' }, 'CoinDCX synced')) onChange() }
  const disconnect = async () => {
    if (!window.confirm('Disconnect CoinDCX? This also removes the synced CoinDCX holdings from this app (nothing changes on CoinDCX).')) return
    if (await run('/coindcx/key', { method: 'DELETE' }, 'CoinDCX disconnected')) {
      setStatus(s => s && { ...s, linked: false })
      onChange()
    }
  }

  if (!status || !status.available) return null
  return (
    <section className="card setup">
      <div className="kv">
        <strong>CoinDCX</strong>
        {status.linked ? (
          <span className="row">
            <button type="button" onClick={sync} disabled={busy}>{busy ? 'Syncing...' : 'Sync now'}</button>
            <button type="button" className="danger" onClick={disconnect} disabled={busy}>Disconnect</button>
          </span>
        ) : (
          <button type="button" onClick={() => setOpen(o => !o)}>{open ? 'Close' : 'Connect'}</button>
        )}
      </div>
      {status.linked && <small className="muted">Balances are pulled from your CoinDCX account. Add what you paid so profit can be shown.</small>}
      {open && !status.linked && (
        <form onSubmit={connect} className="form">
          <ol>
            <li>On the CoinDCX <b>website</b> (not the phone app), click your profile icon, then <b>Profile</b>, then <b>API Dashboard</b>, and create a new key.</li>
            <li>CoinDCX does not offer a read-only option, so this key may also be able to trade. Only continue if you are comfortable with that, and revoke it any time from the same page. Do not bind an IP address.</li>
            <li>Paste the key and secret here. They are encrypted before being stored.</li>
          </ol>
          <input placeholder="API key" value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="off" aria-label="API key" />
          <input placeholder="API secret" type="password" value={apiSecret} onChange={e => setApiSecret(e.target.value)} autoComplete="off" aria-label="API secret" />
          <button className="primary" disabled={busy || !apiKey || !apiSecret}>{busy ? 'Connecting...' : 'Connect and sync'}</button>
        </form>
      )}
    </section>
  )
}

function KiteCard({ onChange, showToast }: { onChange: () => void; showToast: (t: { msg: string }) => void }) {
  const [status, setStatus] = useState<{ available: boolean; linked: boolean; connected: boolean } | null>(null)
  const [open, setOpen] = useState(false)
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [busy, setBusy] = useState(false)
  const callback = `${window.location.origin}/api/kite/callback`

  const refresh = useCallback(() => {
    api('/kite/status').then(r => r.json()).then(setStatus).catch(() => setStatus({ available: false, linked: false, connected: false }))
  }, [])
  useEffect(() => { refresh() }, [refresh])

  const login = async () => {
    setBusy(true)
    try {
      const res = await api('/kite/login', { method: 'POST' })
      if (!res.ok) return showToast({ msg: 'Could not start Zerodha login. Please try again.' })
      window.location.href = (await res.json()).url
    } finally {
      setBusy(false)
    }
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const res = await api('/kite/app', { method: 'PUT', body: JSON.stringify({ apiKey, apiSecret }) })
      if (!res.ok) return showToast({ msg: 'Could not save. Please try again.' })
      setApiKey('')
      setApiSecret('')
      setOpen(false)
      await login()
    } finally {
      setBusy(false)
    }
  }

  const sync = async () => {
    setBusy(true)
    try {
      const res = await api('/kite/sync', { method: 'POST' })
      if (res.status === 409) return await login()
      if (!res.ok) return showToast({ msg: 'Zerodha sync failed. Please try again.' })
      showToast({ msg: 'Zerodha synced' })
      onChange()
      refresh()
    } finally {
      setBusy(false)
    }
  }

  const disconnect = async () => {
    if (!window.confirm('Disconnect Zerodha? This removes the synced Zerodha holdings from this app (nothing changes at Zerodha).')) return
    await api('/kite/app', { method: 'DELETE' })
    showToast({ msg: 'Zerodha disconnected' })
    refresh()
    onChange()
  }

  if (!status || !status.available) return null
  return (
    <section className="card setup">
      <div className="kv">
        <strong>Zerodha</strong>
        {status.linked ? (
          <span className="row">
            <button type="button" onClick={sync} disabled={busy}>{busy ? 'Working...' : status.connected ? 'Sync now' : 'Log in and sync'}</button>
            <button type="button" className="danger" onClick={disconnect} disabled={busy}>Disconnect</button>
          </span>
        ) : (
          <button type="button" onClick={() => setOpen(o => !o)}>{open ? 'Close' : 'Connect'}</button>
        )}
      </div>
      {status.linked && <small className="muted">Stocks and Coin funds from your Zerodha account. Zerodha asks you to log in again each day, so press the button when you want fresh numbers.</small>}
      {open && !status.linked && (
        <form onSubmit={save} className="form">
          <ol>
            <li>Sign in at <b>developers.kite.trade</b> and create an app on the free <b>Personal</b> plan.</li>
            <li>Set its <b>Redirect URL</b> to exactly:<br /><code>{callback}</code></li>
            <li>Paste the app's API key and secret below. They are encrypted before being stored.</li>
          </ol>
          <input placeholder="API key" value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="off" aria-label="API key" />
          <input placeholder="API secret" type="password" value={apiSecret} onChange={e => setApiSecret(e.target.value)} autoComplete="off" aria-label="API secret" />
          <button className="primary" disabled={busy || !apiKey || !apiSecret}>{busy ? 'Saving...' : 'Save and log in to Zerodha'}</button>
        </form>
      )}
    </section>
  )
}

function CasCard({ onChange, showToast }: { onChange: () => void; showToast: (t: { msg: string }) => void }) {
  const [available, setAvailable] = useState(false)
  const [open, setOpen] = useState(false)
  const [pan, setPan] = useState('')
  const [boId, setBoId] = useState('')
  const [dob, setDob] = useState('')
  const [otp, setOtp] = useState('')
  const [sessionId, setSessionId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api('/cas/status').then(r => r.json()).then(d => setAvailable(!!d.available)).catch(() => setAvailable(false))
  }, [])

  const fail = async (res: Response) => {
    let msg = 'Something went wrong. Please try again.'
    try { msg = (await res.json()).message || msg } catch { /* keep generic message */ }
    setError(msg)
  }
  const reset = () => { setSessionId(''); setOtp(''); setPan(''); setBoId(''); setDob(''); setOpen(false) }

  const start = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const res = await api('/cas/cdsl/start', { method: 'POST', body: JSON.stringify({ pan: pan.trim(), boId: boId.trim(), dob }) })
      if (!res.ok) return await fail(res)
      setSessionId((await res.json()).sessionId)
    } catch {
      setError('Could not reach the server. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  const verify = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const res = await api('/cas/cdsl/verify', { method: 'POST', body: JSON.stringify({ sessionId, otp: otp.trim(), pan: pan.trim() }) })
      if (!res.ok) return await fail(res)
      showToast({ msg: 'Demat holdings loaded' })
      reset()
      onChange()
    } catch {
      setError('Could not reach the server. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  if (!available) return null
  return (
    <section className="card setup">
      <div className="kv">
        <strong>Demat statement (CDSL / NSDL)</strong>
        <button type="button" onClick={() => { setOpen(o => !o); setError('') }}>{open ? 'Close' : 'Load holdings'}</button>
      </div>
      {!open && <small className="muted">Loads stocks and funds from all your brokers using your CDSL statement and a phone OTP.</small>}
      {open && !sessionId && (
        <form onSubmit={start} className="form">
          <small>Your PAN, client ID and date of birth are sent only to fetch the statement and are not saved. You need at least one CDSL demat account. The statement also lists NSDL holdings.</small>
          <input placeholder="PAN" value={pan} onChange={e => setPan(e.target.value.toUpperCase())} maxLength={10} autoComplete="off" aria-label="PAN" />
          <input placeholder="16-digit CDSL client ID" inputMode="numeric" value={boId} onChange={e => setBoId(e.target.value.replace(/\D/g, ''))} maxLength={16} autoComplete="off" aria-label="CDSL client ID" />
          <input type="date" value={dob} onChange={e => setDob(e.target.value)} aria-label="Date of birth" />
          <button className="primary" disabled={busy || pan.length !== 10 || boId.length !== 16 || !dob}>{busy ? 'Sending OTP (up to 30 seconds)...' : 'Send OTP'}</button>
        </form>
      )}
      {open && sessionId && (
        <form onSubmit={verify} className="form">
          <small>Enter the OTP CDSL sent to your registered mobile number.</small>
          <input placeholder="OTP" inputMode="numeric" value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, ''))} maxLength={8} autoComplete="one-time-code" aria-label="OTP" />
          <button className="primary" disabled={busy || otp.length < 4}>{busy ? 'Loading holdings...' : 'Load holdings'}</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  )
}

function Investments({ showToast }: { showToast: (t: { msg: string; undo?: () => void }) => void }) {
  const [data, setData] = useState<Portfolio | null>(null)
  const [loading, setLoading] = useState(true)
  const [editId, setEditId] = useState<number | null>(null)
  const [eUnits, setEUnits] = useState('')
  const [eInvested, setEInvested] = useState('')
  const slow = useSlow(loading)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const res = await api('/holdings')
      if (res.ok) setData(await res.json())
    } catch {
      if (!silent) showToast({ msg: 'Could not load. Check your connection.' })
    } finally {
      if (!silent) setLoading(false)
    }
  }, [showToast])
  useEffect(() => { load() }, [load])
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') load(true) }
    const t = setInterval(refresh, 60000)
    document.addEventListener('visibilitychange', refresh)
    return () => { clearInterval(t); document.removeEventListener('visibilitychange', refresh) }
  }, [load])

  const startEdit = (h: HoldingRow) => { setEditId(h.id); setEUnits(String(h.units)); setEInvested(h.invested == null ? '' : String(h.invested)) }
  const saveEdit = async (h: HoldingRow) => {
    const u = Number(eUnits.replace(',', '.'))
    const i = Number(eInvested.replace(',', '.'))
    if (!(u > 0) || !(i >= 0) || eInvested.trim() === '') return showToast({ msg: 'Enter valid numbers' })
    const res = await api(`/holdings/${h.id}`, { method: 'PUT', body: JSON.stringify({ type: h.type, symbol: h.symbol, name: h.name, units: u, invested: i }) })
    if (!res.ok) return showToast({ msg: 'Could not save. Please try again.' })
    setEditId(null)
    showToast({ msg: 'Saved' })
    load()
  }
  const remove = async (h: HoldingRow) => {
    const res = await api(`/holdings/${h.id}`, { method: 'DELETE' })
    if (!res.ok) return showToast({ msg: 'Could not delete. Please try again.' })
    setEditId(null)
    load()
    showToast({
      msg: 'Deleted',
      undo: async () => {
        await api('/holdings', { method: 'POST', body: JSON.stringify({ type: h.type, symbol: h.symbol, name: h.name, units: h.units, invested: h.invested }) })
        load()
      },
    })
  }

  const gain = data ? data.totalGain : 0
  const pct = data && data.totalInvested > 0 ? (gain / data.totalInvested) * 100 : null

  return (
    <>
      <section className="card summary">
        <div className="muted center">Current value</div>
        <div className="grand">{data ? money(data.totalValue) : <span className="skeleton w60" />}</div>
        {data && (
          <div className="center">
            Invested {money(data.totalInvested)}
            {data.totalInvested > 0 && <strong style={{ color: gainColor(gain), marginLeft: 8 }}>{signed(gain)}{pct != null ? ` (${pct.toFixed(2)}%)` : ''}</strong>}
          </div>
        )}
        {data && data.unknownCost > 0 && <p className="muted center">{data.unknownCost} holding(s) have no cost entered, so they are left out of profit and loss. Tap one to add it.</p>}
        {data && !data.complete && <p className="muted center">Some prices are unavailable right now. Those holdings are counted at the amount invested.</p>}
      </section>

      <CasCard onChange={load} showToast={showToast} />

      <KiteCard onChange={load} showToast={showToast} />

      <CoinDcxCard onChange={load} showToast={showToast} />

      {loading && slow && <p className="muted center">{WAKING}</p>}
      {data?.holdings.map(h => editId === h.id ? (
        <section key={h.id} className="card form">
          <strong>{h.name}</strong>
          <input inputMode="decimal" value={eUnits} onChange={e => setEUnits(e.target.value)} aria-label="Units" disabled={h.type === 'coindcx' || h.source === 'kite' || h.source === 'cas'} />
          <input inputMode="decimal" placeholder="Total amount invested (INR)" value={eInvested} onChange={e => setEInvested(e.target.value)} aria-label="Amount invested" disabled={h.source === 'kite'} />
          {h.source === 'kite' && <small>This holding comes from Zerodha. Use Sync now to refresh it.</small>}
          {h.source === 'cas' && <small>Units and value come from your demat statement. Add what you paid in total to see profit.</small>}
          {h.type === 'coindcx' && <small>Units come from CoinDCX. Add what you paid in total so profit can be shown.</small>}
          <div className="row">
            {h.source !== 'kite' && <button className="primary" onClick={() => saveEdit(h)}>Save</button>}
            <button onClick={() => setEditId(null)}>Cancel</button>
            {h.type !== 'coindcx' && h.source !== 'kite' && <button className="danger" onClick={() => remove(h)}>Delete</button>}
          </div>
        </section>
      ) : (
        <button key={h.id} className="card item" onClick={() => startEdit(h)}>
          <span className="desc">
            <span>{h.name}</span>
            <small>{h.units} {h.type === 'mf' ? 'units' : 'qty'} &middot; {h.invested == null ? 'add cost' : `invested ${money(h.invested)}`}{h.source === 'cas' ? ' \u00b7 statement value' : ''}</small>
          </span>
          <span className="right">
            <strong>{h.value != null ? money(h.value) : 'Price n/a'}</strong>
            {h.gain != null && <small style={{ color: gainColor(h.gain) }}>{signed(h.gain)}{h.gainPct != null ? ` (${h.gainPct.toFixed(2)}%)` : ''}</small>}
          </span>
        </button>
      ))}
      {data?.holdings.length === 0 && !loading && <p className="muted center">No holdings yet. Connect Zerodha or CoinDCX above and they load automatically.</p>}
    </>
  )
}

function Tracker({ onLogout }: { onLogout: () => void }) {
  const [month, setMonth] = useState(today().slice(0, 7))
  const [expenses, setExpenses] = useState<Expense[] | null>(null)
  const [totals, setTotals] = useState<Total[]>([])
  const [loading, setLoading] = useState(true)
  const [editId, setEditId] = useState<number | null>(null)
  const [toast, setToast] = useState<ToastState>(null)
  const [showSms, setShowSms] = useState(false)
  const kiteResult = new URLSearchParams(window.location.search).get('kite')
  const [tab, setTab] = useState<'expenses' | 'investments'>(kiteResult ? 'investments' : 'expenses')
  const [category, setCategory] = useState(localStorage.getItem('lastCategory') || CATEGORIES[0])
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const reqId = useRef(0)
  const slow = useSlow(loading)
  const user: UserInfo = JSON.parse(localStorage.getItem('user') || '{}')

  const showToast = useCallback((t: NonNullable<ToastState>) => {
    clearTimeout(toastTimer.current)
    setToast(t)
    toastTimer.current = setTimeout(() => setToast(null), 6000)
  }, [])

  const load = useCallback(async (silent = false) => {
    const id = ++reqId.current
    if (!silent) setLoading(true)
    try {
      const [a, b] = await Promise.all([api(`/expenses?month=${month}`), api(`/expenses/summary?month=${month}`)])
      if (id !== reqId.current) return
      if (a.ok && b.ok) {
        setExpenses(await a.json())
        setTotals(await b.json())
      }
    } catch {
      if (id === reqId.current && !silent) showToast({ msg: 'Could not load. Check your connection.' })
    } finally {
      if (id === reqId.current && !silent) setLoading(false)
    }
  }, [month])
  useEffect(() => { load() }, [load])
  useEffect(() => {
    if (!kiteResult) return
    window.history.replaceState({}, '', window.location.pathname)
    showToast({ msg: kiteResult === 'ok' ? 'Zerodha connected' : 'Zerodha login did not complete. Please try again.' })
  }, [kiteResult, showToast])
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') load(true) }
    const t = setInterval(refresh, 30000)
    document.addEventListener('visibilitychange', refresh)
    return () => { clearInterval(t); document.removeEventListener('visibilitychange', refresh) }
  }, [load])

  const payload = (v: Values) => ({
    description: v.description.trim() || v.category,
    amount: Number(v.amount),
    category: v.category,
    spentOn: v.spentOn,
  })

  const add = async (v: Values) => {
    const res = await api('/expenses', { method: 'POST', body: JSON.stringify(payload(v)) })
    if (!res.ok) return false
    localStorage.setItem('lastCategory', v.category)
    setCategory(v.category)
    showToast({ msg: 'Added' })
    if (v.spentOn.slice(0, 7) !== month) setMonth(v.spentOn.slice(0, 7))
    else load()
    return true
  }

  const update = (id: number) => async (v: Values) => {
    const res = await api(`/expenses/${id}`, { method: 'PUT', body: JSON.stringify(payload(v)) })
    if (!res.ok) return false
    setEditId(null)
    showToast({ msg: 'Saved' })
    load()
    return true
  }

  const remove = async (x: Expense) => {
    const res = await api(`/expenses/${x.id}`, { method: 'DELETE' })
    if (!res.ok) return showToast({ msg: 'Could not delete. Please try again.' })
    setEditId(null)
    load()
    showToast({
      msg: 'Deleted',
      undo: async () => {
        await api('/expenses', { method: 'POST', body: JSON.stringify({ description: x.description, amount: x.amount, category: x.category, spentOn: x.spentOn }) })
        setToast(null)
        load()
      },
    })
  }

  const shift = (delta: number) => {
    const [y, m] = month.split('-').map(Number)
    const d = new Date(y, m - 1 + delta, 1)
    setMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
    setEditId(null)
  }

  const grand = totals.reduce((s, t) => s + Number(t.total), 0)
  const label = new Date(`${month}-01T00:00`).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  const isCurrent = month >= today().slice(0, 7)

  return (
    <main className="app">
      <header className="top">
        <div className="who">
          {user.picture && <img src={user.picture} alt="" referrerPolicy="no-referrer" />}
          <span>{user.name || user.email}</span>
        </div>
        <div>
          <button className="link" onClick={() => setShowSms(s => !s)}>Auto-add</button>
          <button className="link" onClick={() => { clearSession(); onLogout() }}>Sign out</button>
        </div>
      </header>
      {showSms && <SmsSetup />}

      <nav className="tabs">
        <button className={tab === 'expenses' ? 'on' : ''} onClick={() => setTab('expenses')}>Expenses</button>
        <button className={tab === 'investments' ? 'on' : ''} onClick={() => setTab('investments')}>Investments</button>
      </nav>

      {tab === 'investments' ? <Investments showToast={showToast} /> : <>
      <EntryForm label="Add expense" focus initial={{ amount: '', description: '', category, spentOn: today() }} onSubmit={add} />

      <section className="card summary">
        <div className="months">
          <button aria-label="Previous month" onClick={() => shift(-1)}>&lsaquo;</button>
          <strong>{label}</strong>
          <button aria-label="Next month" disabled={isCurrent} onClick={() => shift(1)}>&rsaquo;</button>
        </div>
        <div className="grand">{loading && expenses === null ? <span className="skeleton w60" /> : money(grand)}</div>
        {totals.map(t => (
          <div key={t.category} className="cat">
            <span className="dot" style={{ background: COLORS[t.category] || COLORS.Other }} />
            <span className="name">{t.category}</span>
            <span>{money(Number(t.total))}</span>
            <span className="bar"><i style={{ width: `${grand ? (Number(t.total) / grand) * 100 : 0}%`, background: COLORS[t.category] || COLORS.Other }} /></span>
          </div>
        ))}
      </section>

      {loading && slow && <p className="muted center">{WAKING}</p>}
      {loading && expenses === null && [1, 2, 3].map(i => <div key={i} className="card skeleton h56" />)}

      {expenses?.map(x => editId === x.id ? (
        <EntryForm key={x.id} label="Save" focus
          initial={{ amount: String(x.amount), description: x.description, category: x.category, spentOn: x.spentOn }}
          onSubmit={update(x.id)} onCancel={() => setEditId(null)} onDelete={() => remove(x)} />
      ) : (
        <button key={x.id} className="card item" onClick={() => setEditId(x.id)}>
          <span className="dot" style={{ background: COLORS[x.category] || COLORS.Other }} />
          <span className="desc">
            <span>{x.description}</span>
            <small>{x.category} &middot; {x.source === 'sms' ? 'auto \u00b7 ' : ''}{new Date(`${x.spentOn}T00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</small>
          </span>
          <strong>{money(Number(x.amount))}</strong>
        </button>
      ))}
      {expenses?.length === 0 && !loading && <p className="muted center">No expenses in {label} yet. Add your first one above.</p>}
      </>}

      {toast && (
        <div className="toast" role="status">
          <span>{toast.msg}</span>
          {toast.undo && <button onClick={toast.undo}>Undo</button>}
        </div>
      )}
    </main>
  )
}

export default function App() {
  const [authed, setAuthed] = useState(!!localStorage.getItem('token'))
  const onLogin = useCallback(() => setAuthed(true), [])
  return authed ? <Tracker onLogout={() => setAuthed(false)} /> : <Login onLogin={onLogin} />
}
