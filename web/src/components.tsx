import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { api, errorMessage } from './api';
import { date, labels, money } from './money';
import type { Operation, Page } from './types';

export function useRemote<T>(path: string, version: number) {
	const [data, setData] = useState<{path:string;value:T} | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true), [tick, setTick] = useState(0);
	useEffect(() => { let active = true; setBusy(true); setError('');
		api<T>(path).then(value => { if(active) setData({path,value}); }).catch(e => { if(active) setError(errorMessage(e)); }).finally(() => { if(active) setBusy(false); });
		return () => { active = false; };
	}, [path, version, tick]);
	return {data:data?.path===path?data.value:null, error, busy, reload: () => setTick(value => value + 1)};
}
export function Notice({children, error = false}: {children:ReactNode;error?:boolean}) { return <div className={`notice ${error ? 'danger' : ''}`} role={error ? 'alert' : 'status'}>{children}</div>; }
export function Empty({title='Здесь пока пусто', children}: {title?:string;children?:ReactNode}) { return <div className="empty"><span className="empty-icon">≋</span><h3>{title}</h3><p>{children || 'Новые записи появятся после первой операции.'}</p></div>; }
export function Badge({state}: {state:string}) { return <span className={`badge ${state.toLowerCase()}`}>{labels[state] || state}</span>; }
export function Id({value}: {value:string | null}) { return value ? <code className="identifier" title={value}>{value}</code> : <span className="muted">—</span>; }
export function Pager({offset, count, change}: {offset:number;count:number;change:(n:number)=>void}) {
	return <div className="pager"><span>{count?`Записи ${offset+1}–${offset+count}`:'Нет загруженных записей'}</span><button className="secondary" disabled={offset === 0} onClick={() => change(Math.max(0, offset - 20))}>Назад</button><button className="secondary" disabled={count < 20} onClick={() => change(offset + 20)}>Далее</button></div>;
}
export function OperationRows({items, onOpen}: {items:Operation[];onOpen?:(id:string)=>void}) {
	return <div className="table-scroll"><table><thead><tr><th>Операция</th><th>Дата</th><th>Сумма / комиссия</th><th>Состояние</th></tr></thead><tbody>{items.map(item => <tr key={item.id}><td>{onOpen ? <button className="text-button operation-link" onClick={()=>onOpen(item.id)} aria-label={`Открыть операцию ${item.id}`}>{labels[item.type] || item.type}</button> : <strong>{labels[item.type] || item.type}</strong>}<Id value={item.id}/>{item.original_operation_id && <small>Возврат <Id value={item.original_operation_id}/></small>}</td><td>{date(item.created_at)}</td><td className="nowrap"><strong>{money(item.amount_minor)}</strong><small>Комиссия {money(item.fee_minor)}</small></td><td><Badge state={item.state}/></td></tr>)}</tbody></table></div>;
}
export function History({version, admin = false}: {version:number;admin?:boolean}) {
	const [offset, setOffset] = useState(0);
	const [query,setQuery] = useState(''), [selected,setSelected] = useState<string|null>(null);
	function find(event:FormEvent) {event.preventDefault();setSelected(query.trim());}
	const state = useRemote<Page<Operation>>(`${admin ? '/admin' : ''}/operations?limit=20&offset=${offset}`, version);
	return <section className="panel"><div className="section-head"><div><span className="eyebrow">ЖУРНАЛ</span><h2>{admin ? 'Все операции' : 'История операций'}</h2></div><button className="secondary" disabled={state.busy} onClick={state.reload}>Обновить</button></div>
		<form className="operation-search" onSubmit={find}><label>Найти по номеру операции<input required pattern="[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}" placeholder="UUID операции" value={query} onChange={event=>setQuery(event.target.value)}/></label><button className="secondary">Открыть</button></form>
		{state.error && <Notice error>{state.error}</Notice>}{state.busy ? <p className="loading" role="status">Загрузка операций…</p> : state.data?.items.length ? <OperationRows items={state.data.items} onOpen={setSelected}/> : !state.error && <Empty/>}
		<Pager offset={offset} count={state.data?.items.length || 0} change={setOffset}/>{selected && <OperationDetails key={selected} id={selected} admin={admin} onClose={()=>setSelected(null)}/>}</section>;
}

function OperationDetails({id,admin,onClose}: {id:string;admin:boolean;onClose:()=>void}) {
	const dialog=useRef<HTMLDialogElement>(null);
	const state=useRemote<Operation>(`${admin?'/admin':''}/operations/${id}`,0);
	useEffect(()=>{dialog.current?.showModal();},[]);
	return <dialog ref={dialog} onCancel={event=>{event.preventDefault();onClose();}} aria-labelledby="operation-title">
		<span className="eyebrow">ИСТОРИЯ КОШЕЛЬКА</span><h2 id="operation-title">Детали операции</h2>
		{state.busy?<p role="status">Загрузка операции…</p>:state.error?<Notice error>{state.error} <button className="text-button" onClick={state.reload}>Повторить</button></Notice>:state.data&&<>
			<Badge state={state.data.state}/><dl>
				<div><dt>Номер</dt><dd><Id value={state.data.id}/></dd></div>
				<div><dt>Тип</dt><dd>{labels[state.data.type]||state.data.type}</dd></div>
				<div><dt>Дата</dt><dd>{date(state.data.created_at)}</dd></div>
				<div><dt>Сумма</dt><dd>{money(state.data.amount_minor)}</dd></div>
				<div><dt>Комиссия</dt><dd>{money(state.data.fee_minor)}</dd></div>
				<div><dt>Сумма с комиссией</dt><dd>{money(state.data.total_minor)}</dd></div>
				<div><dt>Кошелёк отправителя</dt><dd><Id value={state.data.source_wallet_id}/></dd></div>
				<div><dt>Кошелёк получателя</dt><dd><Id value={state.data.target_wallet_id}/></dd></div>
				{state.data.original_operation_id&&<div><dt>Исходная операция</dt><dd><Id value={state.data.original_operation_id}/></dd></div>}
			</dl>
		</>}
		<div className="dialog-actions"><button className="secondary" onClick={onClose}>Закрыть</button></div>
	</dialog>;
}
