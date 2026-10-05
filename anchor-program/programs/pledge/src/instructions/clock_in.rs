use anchor_lang::prelude::*;

use crate::errors::PledgeError;
use crate::state::{ClockInEvent, Commitment};

#[derive(Accounts)]
pub struct ClockIn<'info> {
    /// The owner wallet or the device session key. It also pays the fee.
    #[account(mut)]
    pub signer: Signer<'info>,

    #[account(
        mut,
        constraint = !commitment.settled @ PledgeError::AlreadySettled,
    )]
    pub commitment: Account<'info, Commitment>,
}

/// The step count is reported by the phone and is NOT verified on chain: the program
/// enforces who may clock in, which day it is (by chain time) and once per day.
pub fn handle_clock_in(ctx: Context<ClockIn>, day_index: u8, steps_reported: u32) -> Result<()> {
    let commitment = &mut ctx.accounts.commitment;
    let signer_key = ctx.accounts.signer.key();

    let is_owner = signer_key == commitment.authority;
    let is_session_key = commitment.clock_in_authority != Pubkey::default()
        && signer_key == commitment.clock_in_authority;
    require!(is_owner || is_session_key, PledgeError::UnauthorizedClockIn);

    require!(steps_reported >= commitment.target_steps, PledgeError::TargetNotMet);
    require!(day_index < commitment.total_days, PledgeError::InvalidDayWindow);

    let day_mask = 1u64 << (day_index as u64);
    require!(
        (commitment.clocked_in_bitmap & day_mask) == 0,
        PledgeError::DayAlreadyClockedIn
    );

    let now = Clock::get()?.unix_timestamp;
    let day_start = commitment
        .start_timestamp
        .checked_add((day_index as i64) * (commitment.day_duration_sec as i64))
        .ok_or(PledgeError::MathOverflow)?;
    let day_end = day_start
        .checked_add(commitment.day_duration_sec as i64)
        .ok_or(PledgeError::MathOverflow)?;
    require!(now >= day_start && now < day_end, PledgeError::InvalidDayWindow);

    commitment.clocked_in_bitmap |= day_mask;
    commitment.completed_days = commitment
        .completed_days
        .checked_add(1)
        .ok_or(PledgeError::MathOverflow)?;

    emit!(ClockInEvent {
        commitment: commitment.key(),
        day_index,
        steps_reported,
        completed_days_so_far: commitment.completed_days,
        timestamp: now,
    });

    Ok(())
}
