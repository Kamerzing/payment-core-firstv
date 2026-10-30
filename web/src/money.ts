const MAX = 9223372036854775807n;
export function toMinor(input: string): string {
	const normalized = input.trim().replace(',', '.');
	if (!/^(0|[1-9]\d*)(\.\d{1,2})?$/.test(normalized)) throw new Error('Введите сумму в рублях, не более двух знаков после запятой.');
	const [rubles, kopecks = ''] = normalized.split('.');
	const value = BigInt(rubles) * 100n + BigInt(kopecks.padEnd(2, '0'));
	if (value <= 0n || value > MAX) throw new Error('Сумма должна быть положительной и не превышать предел системы.');
	return value.toString();
}
export function money(value: string): string {
	const amount = BigInt(value), abs = amount < 0n ? -amount : amount;
	return `${amount < 0n ? '−' : ''}${(abs / 100n).toLocaleString('ru-RU')},${(abs % 100n).toString().padStart(2, '0')} ₽`;
}
export const date = (value: string) => new Date(value).toLocaleString('ru-RU', {day:'2-digit',month:'short',year:'numeric',hour:'2-digit',minute:'2-digit'});
export const labels: Record<string,string> = { DEPOSIT:'Пополнение',TRANSFER:'Перевод',WITHDRAWAL:'Списание',REVERSAL:'Возврат',HOLD_CREATE:'Создание резерва',HOLD_CANCEL:'Отмена резерва',HOLD_EXPIRE:'Истечение резерва',CAPTURE_TRANSFER:'Перевод из резерва',CAPTURE_WITHDRAWAL:'Списание из резерва',ACTIVE:'Активен',CAPTURED:'Подтверждён',CANCELLED:'Отменён',EXPIRED:'Истёк',POSTED:'Проведена' };
