use anchor_lang::prelude::*;

/// The test SKR mint on devnet. Its mint authority is this program's `faucet` PDA, so
/// anyone can claim test tokens from the program and nobody holds a minting key.
pub const SKR_MINT: Pubkey = pubkey!("F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ");
/// One faucet claim: 10,000 test SKR.
pub const FAUCET_AMOUNT: u64 = 10_000 * 1_000_000_000;
/// A wallet may claim only while it holds less than this (5,000 test SKR).
pub const FAUCET_CAP: u64 = 5_000 * 1_000_000_000;

pub const COMMITMENT_SEED: &[u8] = b"commitment";
pub const VAULT_SEED: &[u8] = b"vault";
pub const FAUCET_SEED: &[u8] = b"faucet";
pub const PROFILE_SEED: &[u8] = b"profile";

/// What a pledge measures, and so how `clock_in`'s reported value and time are checked.
pub mod kind {
    /// Steps: value >= target, any time in the day.
    pub const STEPS: u8 = 0;
    /// Screen time in minutes: value <= target, only in the LAST `window` seconds of the
    /// day (a ceiling can only be shown once the day is nearly over).
    pub const SCREEN: u8 = 1;
    /// Wake-up: no value, only in the FIRST `window` seconds of the day. The one habit the
    /// chain checks by itself: Solana's clock decides whether you were up in time.
    pub const WAKE: u8 = 2;
    pub const COUNT: u8 = 3;
}

#[account]
pub struct Commitment {
    /// The wallet that staked and receives the refund.
    pub authority: Pubkey,
    /// Optional device key allowed to call clock_in (and nothing else).
    pub clock_in_authority: Pubkey,
    pub token_mint: Pubkey,
    /// PDA token account holding the stake.
    pub vault: Pubkey,
    /// Daily target: steps (minimum) or screen minutes (maximum); unused for wake-up.
    pub target_steps: u32,
    /// Number of days, 1..=64.
    pub total_days: u8,
    pub completed_days: u8,
    /// Length of one "day" in seconds: 86400 normally, shorter for a demo run.
    pub day_duration_sec: u64,
    /// Chain time when day 0 starts; day i is [start + i*day, start + (i+1)*day).
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
    /// One of `kind::*`.
    pub kind: u8,
    /// Clock-in window in seconds (see `kind`); 0 means the whole day.
    pub window_sec: u32,
}

impl Commitment {
    pub const LEN: usize = 8 + // discriminator
        32 + 32 + 32 + 32 + // authority, clock_in_authority, token_mint, vault
        4 + 1 + 1 + // target_steps, total_days, completed_days
        8 + 8 + 8 + // day_duration_sec, start_timestamp, total_amount
        1 + 8 + // settled, clocked_in_bitmap
        1 + 1 + // bump, vault_bump
        8 + // commitment_id
        1 + 4 + // kind, window_sec
        19; // reserved
}

/// Everyone's lifetime record, one per wallet. It cannot be transferred or sold (it is a
/// program account, not a token), so it is the soulbound part: badges and ranks read it.
#[account]
pub struct Profile {
    pub authority: Pubkey,
    pub pledges_started: u32,
    pub pledges_settled: u32,
    /// Pledges settled with every day kept.
    pub perfect_pledges: u32,
    pub days_kept: u32,
    pub days_missed: u32,
    pub total_staked: u64,
    pub total_returned: u64,
    pub total_burned: u64,
    /// Longest run of consecutive kept days inside one pledge.
    pub best_streak: u8,
    /// Bit k set once a pledge of kind k was settled perfect.
    pub perfect_kinds: u8,
    pub bump: u8,
    /// Days kept in REAL pledges (days of an hour or longer). Ranks sort on this, and
    /// badges (perfect pledges, streaks) only count real pledges: demo days of a minute
    /// would let anyone farm them in minutes.
    pub real_days_kept: u32,
}

impl Profile {
    pub const LEN: usize = 8 + 32 + 4 * 5 + 8 * 3 + 1 + 1 + 1 + 4 + 28; // + reserved (unchanged total: 119)
}

/** A day shorter than this is a demo day: it moves money but earns no badges or rank. */
pub const MIN_REAL_DAY_SEC: u64 = 3_600;

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
    pub kind: u8,
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
