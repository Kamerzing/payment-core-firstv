import { describe, expect, it } from 'vitest';
import { money, toMinor } from './money';
import { ApiFailure, definitelyRejected } from './api';
describe('exact monetary amounts',()=>{
	it('converts rubles without floating point or rounding',()=>{
		expect(toMinor('0,01')).toBe('1'); expect(toMinor('100.10')).toBe('10010');
		expect(toMinor('92233720368547758.07')).toBe('9223372036854775807');
		expect(money('9007199254740993')).toContain(',93'); expect(money('-101')).toBe('−1,01 ₽');
	});
	it('rejects excess precision, signs and overflow',()=>{
		for(const value of ['0','-1','+1','01','1.001','1e3','1,2,3','92233720368547758.08']) expect(()=>toMinor(value)).toThrow();
	});
});
it('retains keys for ambiguous network and server failures',()=>{
	expect(definitelyRejected(new Error('network'))).toBe(false);
	expect(definitelyRejected(new ApiFailure(500,'INTERNAL_ERROR',''))).toBe(false);
	expect(definitelyRejected(new ApiFailure(503,'RETRYABLE_FAILURE',''))).toBe(false);
	expect(definitelyRejected(new ApiFailure(409,'INSUFFICIENT_FUNDS',''))).toBe(true);
});
it('retains keys when authentication or infrastructure blocks a retry',()=>{
	for(const [status,code] of [[401,'UNAUTHORIZED'],[403,'CSRF_INVALID'],[403,'ACCESS_DENIED'],[408,'TIMEOUT'],[409,'IDEMPOTENCY_CONFLICT'],[429,'RATE_LIMITED'],[404,'NOT_FOUND']] as const) {
		expect(definitelyRejected(new ApiFailure(status,code,''))).toBe(false);
	}
});
