import { useCallback, useEffect, useRef, useState } from 'react'

type Expense = { id: number; description: string; amount: number; category: string; spentOn: string }
type Total = { category: string; total: number }

const CATEGORIES = ['Food', 'Transport', 'Bills', 'Shopping', 'Health', 'Entertainment', 'Other']
const today = () => new Date().toISOString().slice(0, 10)
const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function api(path: string, init: RequestInit = {}) {
  const token = localStorage.getItem('token')
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  })
  if (res.status === 401 && !path.startsWith('/auth/')) {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    window.location.reload()
  }
  return res
}

declare global {
  interface Window { google?: any }
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const buttonRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    const init = async () => {
      const cfg = await (await fetch('/api/auth/config')).json()
      if (!cfg.googleClientId) return setError('Google sign-in is not configured yet')
      await new Promise<void>((resolve, reject) => {
        if (window.google?.accounts) return resolve()
        const s = document.createElement('script')
        s.src = 'https://accounts.google.com/gsi/client'
        s.onload = () => resolve()
        s.onerror = () => reject(new Error('Could not load Google sign-in'))
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
            if (!res.ok) throw new Error(res.status === 401 ? 'Google sign-in was rejected' : 'Server error')
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
    init().catch(err => setError((err as Error).message || 'Could not reach the server'))
    return () => { cancelled = true }
  }, [onLogin])

  return (
    <div style={{ maxWidth: 320, margin: '4rem auto', display: 'grid', gap: 12, fontFamily: 'sans-serif', textAlign: 'center', padding: '0 1rem' }}>
      <h1>Expense Tracker</h1>
      <p>Sign in or sign up with your Google account.</p>
      <div ref={buttonRef} style={{ display: 'flex', justifyContent: 'center', minHeight: 44 }} />
      {busy && <p>Signing in (server may be waking up)...</p>}
      {error && <p style={{ color: 'crimson' }}>{error}</p>}
    </div>
  )
}

function Tracker({ onLogout }: { onLogout: () => void }) {
  const [month, setMonth] = useState(today().slice(0, 7))
  const [expenses, setExpenses] = useState<Expense[]>([])
  const [totals, setTotals] = useState<Total[]>([])
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [category, setCategory] = useState(CATEGORIES[0])
  const [spentOn, setSpentOn] = useState(today())
  const [error, setError] = useState('')
  const user: { name?: string; email?: string } = JSON.parse(localStorage.getItem('user') || '{}')

  const load = useCallback(async () => {
    const [a, b] = await Promise.all([api(`/expenses?month=${month}`), api(`/expenses/summary?month=${month}`)])
    if (a.ok && b.ok) {
      setExpenses(await a.json())
      setTotals(await b.json())
    }
  }, [month])
  useEffect(() => { load() }, [load])

  const add = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    const res = await api('/expenses', {
      method: 'POST',
      body: JSON.stringify({ description, amount: Number(amount), category, spentOn }),
    })
    if (!res.ok) return setError('Enter a description and a positive amount')
    setDescription('')
    setAmount('')
    if (spentOn.slice(0, 7) !== month) setMonth(spentOn.slice(0, 7))
    else load()
  }
  const remove = async (id: number) => {
    await api(`/expenses/${id}`, { method: 'DELETE' })
    load()
  }

  const grand = totals.reduce((s, t) => s + Number(t.total), 0)

  return (
    <div style={{ maxWidth: 640, margin: '2rem auto', fontFamily: 'sans-serif', padding: '0 1rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Expense Tracker</h1>
        <span>{user.name || user.email} <button onClick={() => { localStorage.removeItem('token'); localStorage.removeItem('user'); onLogout() }}>Sign out</button></span>
      </div>

      <form onSubmit={add} style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        <input placeholder="Description" value={description} onChange={e => setDescription(e.target.value)} style={{ flex: 2, minWidth: 140 }} />
        <input placeholder="Amount" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} style={{ width: 90 }} />
        <select value={category} onChange={e => setCategory(e.target.value)}>
          {CATEGORIES.map(c => <option key={c}>{c}</option>)}
        </select>
        <input type="date" value={spentOn} onChange={e => setSpentOn(e.target.value)} />
        <button>Add</button>
      </form>
      {error && <p style={{ color: 'crimson' }}>{error}</p>}

      <h2>
        <input type="month" value={month} onChange={e => e.target.value && setMonth(e.target.value)} />
        {' '}Total: {money(grand)}
      </h2>
      {totals.length > 0 && (
        <ul>{totals.map(t => <li key={t.category}>{t.category}: {money(Number(t.total))}</li>)}</ul>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <tbody>
          {expenses.map(x => (
            <tr key={x.id} style={{ borderBottom: '1px solid #ddd' }}>
              <td>{x.spentOn}</td>
              <td>{x.description}</td>
              <td>{x.category}</td>
              <td style={{ textAlign: 'right' }}>{money(Number(x.amount))}</td>
              <td><button onClick={() => remove(x.id)}>x</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {expenses.length === 0 && <p>No expenses this month.</p>}
    </div>
  )
}

export default function App() {
  const [authed, setAuthed] = useState(!!localStorage.getItem('token'))
  return authed ? <Tracker onLogout={() => setAuthed(false)} /> : <Login onLogin={() => setAuthed(true)} />
}
