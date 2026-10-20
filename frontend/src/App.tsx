import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'

type Expense = { id: number; description: string; amount: number; category: string; spentOn: string }
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

function Tracker({ onLogout }: { onLogout: () => void }) {
  const [month, setMonth] = useState(today().slice(0, 7))
  const [expenses, setExpenses] = useState<Expense[] | null>(null)
  const [totals, setTotals] = useState<Total[]>([])
  const [loading, setLoading] = useState(true)
  const [editId, setEditId] = useState<number | null>(null)
  const [toast, setToast] = useState<ToastState>(null)
  const [category, setCategory] = useState(localStorage.getItem('lastCategory') || CATEGORIES[0])
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const reqId = useRef(0)
  const slow = useSlow(loading)
  const user: UserInfo = JSON.parse(localStorage.getItem('user') || '{}')

  const showToast = (t: NonNullable<ToastState>) => {
    clearTimeout(toastTimer.current)
    setToast(t)
    toastTimer.current = setTimeout(() => setToast(null), 6000)
  }

  const load = useCallback(async () => {
    const id = ++reqId.current
    setLoading(true)
    try {
      const [a, b] = await Promise.all([api(`/expenses?month=${month}`), api(`/expenses/summary?month=${month}`)])
      if (id !== reqId.current) return
      if (a.ok && b.ok) {
        setExpenses(await a.json())
        setTotals(await b.json())
      }
    } catch {
      if (id === reqId.current) showToast({ msg: 'Could not load. Check your connection.' })
    } finally {
      if (id === reqId.current) setLoading(false)
    }
  }, [month])
  useEffect(() => { load() }, [load])

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
        <button className="link" onClick={() => { clearSession(); onLogout() }}>Sign out</button>
      </header>

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
            <small>{x.category} &middot; {new Date(`${x.spentOn}T00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</small>
          </span>
          <strong>{money(Number(x.amount))}</strong>
        </button>
      ))}
      {expenses?.length === 0 && !loading && <p className="muted center">No expenses in {label} yet. Add your first one above.</p>}

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
