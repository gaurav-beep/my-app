import { useEffect, useState } from 'react'

type Task = { id: number; title: string; done: boolean }

const api = (path: string, init?: RequestInit) =>
  fetch(`/api/tasks${path}`, { headers: { 'Content-Type': 'application/json' }, ...init })

export default function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [title, setTitle] = useState('')

  const load = async () => setTasks(await (await api('')).json())
  useEffect(() => { load() }, [])

  const add = async () => {
    if (!title.trim()) return
    await api('', { method: 'POST', body: JSON.stringify({ title, done: false }) })
    setTitle('')
    load()
  }
  const toggle = async (t: Task) => {
    await api(`/${t.id}`, { method: 'PUT', body: JSON.stringify({ ...t, done: !t.done }) })
    load()
  }
  const remove = async (t: Task) => {
    await api(`/${t.id}`, { method: 'DELETE' })
    load()
  }

  return (
    <div style={{ maxWidth: 480, margin: '2rem auto', fontFamily: 'sans-serif' }}>
      <h1>My Tasks</h1>
      <input value={title} onChange={e => setTitle(e.target.value)}
        onKeyDown={e => e.key === 'Enter' && add()} placeholder="New task" />
      <button onClick={add}>Add</button>
      <ul>
        {tasks.map(t => (
          <li key={t.id}>
            <input type="checkbox" checked={t.done} onChange={() => toggle(t)} />
            <span style={{ textDecoration: t.done ? 'line-through' : 'none' }}> {t.title} </span>
            <button onClick={() => remove(t)}>x</button>
          </li>
        ))}
      </ul>
    </div>
  )
}
