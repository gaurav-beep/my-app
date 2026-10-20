import { useCallback, useEffect, useState } from 'react'

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
  if (res.status === 401 && path !== '/login') {
    localStorage.removeItem('token')
    window.location.reload()
  }
  return res
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const res = await api('/login', { method: 'POST', body: JSON.stringify({ username, password }) })
      if (!res.ok) throw new Error(res.status === 401 ? 'Invalid username or password' : 'Server error')
      localStorage.setItem('token', (await res.json()).token)
      onLogin()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} style={{ maxWidth: 320, margin: '4rem auto', display: 'grid', gap: 8, fontFamily: 'sans-serif' }}>
      <h1>Expense Tracker</h1>
      <input placeholder="Username" value={username} onChange={e => setUsername(e.target.value)} autoFocus />
      <input placeholder="Password" type="password" value={password} onChange={e => setPassword(e.target.value)} />
      <button disabled={busy}>{busy ? 'Signing in (server may be waking up)...' : 'Sign in'}</button>
      {error && <p style={{ color: 'crimson' }}>{error}</p>}
    </form>
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
        <button onClick={() => { localStorage.removeItem('token'); onLogout() }}>Sign out</button>
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
