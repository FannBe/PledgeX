use anchor_lang::prelude::*;

/// The test SKR mint on devnet. Its mint authority is this program's `faucet` PDA, so
/// anyone can claim test tokens from the program and nobody holds a minting key.
pub const SKR_MINT: Pubkey = pubkey!("F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ");
pub const SKR_DECIMALS: u8 = 9;
/// One faucet claim: 10,000 test SKR.
pub const FAUCET_AMOUNT: u64 = 10_000 * 1_000_000_000;
/// A wallet may claim only while it holds less than this (5,000 test SKR).
pub const FAUCET_CAP: u64 = 5_000 * 1_000_000_000;

pub const COMMITMENT_SEED: &[u8] = b"commitment";
pub const VAULT_SEED: &[u8] = b"vault";
pub const FAUCET_SEED: &[u8] = b"faucet";

#[account]
pub struct Commitment {
    /// The wallet that staked and receives the refund.
    pub authority: Pubkey,
    /// Optional device key allowed to call clock_in (and nothing else).
    pub clock_in_authority: Pubkey,
    pub token_mint: Pubkey,
    /// PDA token account holding the stake.
    pub vault: Pubkey,
    /// Daily step target.
    pub target_steps: u32,
    /// Number of days, 1..=64.
    pub total_days: u8,
    pub completed_days: u8,
    /// Length of one "day" in seconds: 86400 normally, shorter for a demo run.
    pub day_duration_sec: u64,
    /// Chain time when the commitment was created; day i is
    /// [start + i*day, start + (i+1)*day).
    pub start_timestamp: i64,
    /// Total staked, in base units.
    pub total_amount: u64,
    pub settled: bool,
    /// Bit i is set when day i was clocked in.
    pub clocked_in_bitmap: u64,
    pub bump: u8,
    pub vault_bump: u8,
    /// The id the commitment PDA was derived from.
    pub commitment_id: u64,
}

impl Commitment {
    pub const LEN: usize = 8 + // discriminator
        32 + 32 + 32 + 32 + // authority, clock_in_authority, token_mint, vault
        4 + 1 + 1 + // target_steps, total_days, completed_days
        8 + 8 + 8 + // day_duration_sec, start_timestamp, total_amount
        1 + 8 + // settled, clocked_in_bitmap
        1 + 1 + // bump, vault_bump
        8 + // commitment_id
        24; // reserved
}

#[event]
pub struct CommitmentCreatedEvent {
    pub commitment: Pubkey,
    pub authority: Pubkey,
    pub token_mint: Pubkey,
    pub total_amount: u64,
    pub target_steps: u32,
    pub total_days: u8,
    pub day_duration_sec: u64,
    pub start_timestamp: i64,
}

#[event]
pub struct ClockInEvent {
    pub commitment: Pubkey,
    pub day_index: u8,
    pub steps_reported: u32,
    pub completed_days_so_far: u8,
    pub timestamp: i64,
}

#[event]
pub struct SettledEvent {
    pub commitment: Pubkey,
    pub user: Pubkey,
    pub refunded_amount: u64,
    pub burned_amount: u64,
    pub completed_days: u8,
    pub total_days: u8,
}

#[event]
pub struct FaucetEvent {
    pub user: Pubkey,
    pub amount: u64,
}
