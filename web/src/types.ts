export interface User { id: string; username: string; role: 'USER' | 'ADMIN'; created_at: string }
export interface Wallet { id: string; owner_id: string; owner_username?: string; enabled?: boolean; currency: string; available_minor: string; reserved_minor: string }
export interface Page<T> { items: T[]; limit: number; offset: number }
export interface Operation { id: string; type: string; state: string; source_wallet_id: string | null; target_wallet_id: string | null; amount_minor: string; fee_minor: string; total_minor: string; fee_basis_points: number; original_operation_id: string | null; created_at: string }
export interface Hold { id: string; type: string; state: string; wallet_id: string; target_wallet_id: string | null; amount_minor: string; fee_minor: string; total_minor: string; fee_basis_points: number; expires_at: string; created_at: string; completion_operation_id: string | null }
export interface Quote { type: string; amount_minor: string; fee_minor: string; fee_basis_points: number; total_minor: string }
export interface Audit { id: string; actor_id: string; action: string; operation_id: string; request_id: string; created_at: string }
export interface Reconciliation { consistent: boolean; snapshot_at: string; total_minor: string; invalid_operations: number; accounts: {account_id:string;cached_minor:string;journal_minor:string}[]; reserved_holds: {account_id:string;cached_minor:string;journal_minor:string}[] }
export interface Command { key: string; path: string; body: unknown; title: string; details: [string, string][] }
