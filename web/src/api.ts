export class ApiFailure extends Error {
	constructor(public status: number, public code: string, public requestId: string) { super(code); }
}
const messages: Record<string,string> = { INVALID_CREDENTIALS:'Неверный логин или пароль.', LOGIN_RATE_LIMITED:'Слишком много попыток входа. Попробуйте позже.', INVALID_REQUEST:'Проверьте введённые данные.', INSUFFICIENT_FUNDS:'Недостаточно средств для операции с учётом комиссии.', QUOTE_CHANGED:'Условия изменились. Получите новый расчёт и подтвердите его.', IDEMPOTENCY_CONFLICT:'Этот запрос уже использован с другими условиями.', ALREADY_REVERSED:'Операция уже возвращена.', NOT_REVERSIBLE:'Эту операцию нельзя вернуть.', HOLD_CLOSED:'Резерв уже завершён. Обновите список.', HOLD_EXPIRED:'Срок резерва истёк. Средства освободятся автоматически.', WALLET_NOT_FOUND:'Кошелёк не найден или недоступен.', OPERATION_NOT_FOUND:'Операция не найдена.', HOLD_NOT_FOUND:'Резерв не найден.', UNAUTHORIZED:'Сессия завершена. Войдите снова.', CSRF_INVALID:'Обновите страницу и повторите действие.', ACCESS_DENIED:'Недостаточно прав.', USERNAME_TAKEN:'Такой логин уже занят.', SELF_TRANSFER:'Выберите другой кошелёк.', BALANCE_OVERFLOW:'Получившийся остаток превышает предел системы.', AMOUNT_OVERFLOW:'Сумма с комиссией превышает предел системы.' };
export function errorMessage(error: unknown): string {
	if (error instanceof ApiFailure) return `${messages[error.code] || 'Не удалось выполнить запрос.'}${error.requestId ? ` Номер обращения: ${error.requestId}` : ''}`;
	return error instanceof Error ? error.message : 'Не удалось выполнить запрос.';
}
export async function api<T>(path: string, body?: unknown, key?: string): Promise<T> {
	const headers: Record<string,string> = {};
	if (body !== undefined) {
		const csrf = await api<{header_name:string;token:string}>('/auth/csrf');
		headers[csrf.header_name] = csrf.token; headers['Content-Type'] = 'application/json';
	}
	if (key) headers['Idempotency-Key'] = key;
	let response: Response;
	try {
		response = await fetch('/api/v1' + path, {method: body === undefined ? 'GET' : 'POST', credentials:'same-origin', headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000)});
	} catch { throw new Error('Связь с сервером прервана. Результат пока неизвестен.'); }
	if (!response.ok) {
		const error = await response.json().catch(() => ({}));
		if (response.status === 401 && path !== '/auth/login' && path !== '/auth/me') window.dispatchEvent(new Event('session-ended'));
		throw new ApiFailure(response.status, error.code || 'INTERNAL_ERROR', error.request_id || response.headers.get('X-Request-ID') || '');
	}
	return response.status === 204 ? undefined as T : response.json();
}
export function definitelyRejected(error: unknown): boolean {
	// Authentication, CSRF and intermediary errors do not establish the outcome of
	// an earlier attempt. Keep its key so it can be replayed after access returns.
	return error instanceof ApiFailure && error.status >= 400 && error.status < 500
		&& new Set([
			'INVALID_REQUEST', 'INSUFFICIENT_FUNDS', 'QUOTE_CHANGED', 'ALREADY_REVERSED',
			'NOT_REVERSIBLE', 'HOLD_CLOSED', 'HOLD_EXPIRED', 'WALLET_NOT_FOUND',
			'OPERATION_NOT_FOUND', 'HOLD_NOT_FOUND', 'SELF_TRANSFER', 'BALANCE_OVERFLOW', 'AMOUNT_OVERFLOW',
		]).has(error.code);
}
