use anchor_lang::prelude::*;

use crate::errors::PledgeError;
use crate::state::{kind, ClockInEvent, Commitment};

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

/// The value (steps or screen minutes) is reported by the phone and is NOT verified on
/// chain. The program enforces who may clock in, which day it is and inside which part of
/// the day (by chain time), and once per day. Wake-up pledges need no value at all: the
/// chain's clock alone decides.
pub fn handle_clock_in(ctx: Context<ClockIn>, day_index: u8, steps_reported: u32) -> Result<()> {
    let commitment = &mut ctx.accounts.commitment;
    let signer_key = ctx.accounts.signer.key();

    let is_owner = signer_key == commitment.authority;
    let is_session_key = commitment.clock_in_authority != Pubkey::default()
        && signer_key == commitment.clock_in_authority;
    require!(is_owner || is_session_key, PledgeError::UnauthorizedClockIn);

    match commitment.kind {
        kind::STEPS => require!(steps_reported >= commitment.target_steps, PledgeError::TargetNotMet),
        kind::SCREEN => require!(steps_reported <= commitment.target_steps, PledgeError::LimitExceeded),
        _ => {}
    }
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
    let window = commitment.window_sec as i64;
    if window > 0 {
        match commitment.kind {
            // Up in time: only the first `window` seconds of the day count.
            kind::WAKE => require!(now < day_start + window, PledgeError::InvalidDayWindow),
            // A ceiling is only shown near the end of the day.
            kind::SCREEN => require!(now >= day_end - window, PledgeError::InvalidDayWindow),
            _ => {}
        }
    }

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
