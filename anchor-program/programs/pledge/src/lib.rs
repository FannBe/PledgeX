use anchor_lang::prelude::*;

pub mod errors;
pub mod instructions;
pub mod state;

use instructions::*;

declare_id!("68c1eNdHAfNWJhCWhtqumiqzYyFcDNLkfgwKwdFwFRcd");

#[program]
pub mod pledge {
    use super::*;

    /// Create a new commitment, moving the stake into a PDA vault.
    pub fn create_commitment(
        ctx: Context<CreateCommitment>,
        commitment_id: u64,
        target_steps: u32,
        total_days: u8,
        day_duration_sec: u64,
        amount: u64,
    ) -> Result<()> {
        instructions::create_commitment::handle_create_commitment(
            ctx,
            commitment_id,
            target_steps,
            total_days,
            day_duration_sec,
            amount,
        )
    }

    /// Clock in for one day. Signed by the owner wallet or by the device session key.
    pub fn clock_in(ctx: Context<ClockIn>, day_index: u8, steps_reported: u32) -> Result<()> {
        instructions::clock_in::handle_clock_in(ctx, day_index, steps_reported)
    }

    /// After the last day: refund completed days, burn missed days, close the accounts.
    /// Anyone may call it; the refund and the rent only ever go to the owner.
    pub fn settle(ctx: Context<Settle>) -> Result<()> {
        instructions::settle::handle_settle(ctx)
    }

    /// Mint 10,000 test SKR to the caller while they hold less than 5,000.
    pub fn faucet(ctx: Context<Faucet>) -> Result<()> {
        instructions::faucet::handle_faucet(ctx)
    }
}
