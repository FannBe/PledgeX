use anchor_lang::prelude::*;
use anchor_spl::token::{self, Mint, Token, TokenAccount, Transfer};

use crate::errors::PledgeError;
use crate::state::{kind, Commitment, CommitmentCreatedEvent, Profile, COMMITMENT_SEED, PROFILE_SEED, SKR_MINT, VAULT_SEED};

pub const MIN_DAY_SEC: u64 = 60;
pub const MAX_DAY_SEC: u64 = 7 * 86_400;
/// How far ahead day 0 may start (a 6 AM pledge made in the evening starts tomorrow).
pub const MAX_START_DELAY: i64 = 2 * 86_400;

#[derive(Accounts)]
#[instruction(commitment_id: u64)]
pub struct CreateCommitment<'info> {
    #[account(mut)]
    pub user: Signer<'info>,

    /// CHECK: Only its address is stored; it may sign clock_in and nothing else.
    /// Pass the system program id to have no session key.
    pub clock_in_authority: UncheckedAccount<'info>,

    #[account(
        init,
        payer = user,
        space = Commitment::LEN,
        seeds = [COMMITMENT_SEED, user.key().as_ref(), &commitment_id.to_le_bytes()],
        bump
    )]
    pub commitment: Account<'info, Commitment>,

    #[account(
        init,
        payer = user,
        seeds = [VAULT_SEED, commitment.key().as_ref()],
        bump,
        token::mint = token_mint,
        token::authority = vault
    )]
    pub vault: Account<'info, TokenAccount>,

    #[account(
        init_if_needed,
        payer = user,
        space = Profile::LEN,
        seeds = [PROFILE_SEED, user.key().as_ref()],
        bump
    )]
    pub profile: Account<'info, Profile>,

    #[account(
        mut,
        token::mint = token_mint,
        token::authority = user,
    )]
    pub user_token_account: Account<'info, TokenAccount>,

    /// Stakes are in test SKR only, so a record cannot be built on a worthless token.
    #[account(address = SKR_MINT)]
    pub token_mint: Account<'info, Mint>,

    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
    pub rent: Sysvar<'info, Rent>,
}

#[allow(clippy::too_many_arguments)]
pub fn handle_create_commitment(
    ctx: Context<CreateCommitment>,
    commitment_id: u64,
    target_steps: u32,
    total_days: u8,
    day_duration_sec: u64,
    amount: u64,
    pledge_kind: u8,
    start_at: i64,
    window_sec: u32,
) -> Result<()> {
    require!((1..=64).contains(&total_days), PledgeError::InvalidTotalDays);
    require!(
        (MIN_DAY_SEC..=MAX_DAY_SEC).contains(&day_duration_sec),
        PledgeError::InvalidDuration
    );
    require!(amount > 0, PledgeError::InvalidAmount);
    require!(pledge_kind < kind::COUNT, PledgeError::InvalidKind);
    require!(target_steps > 0 || pledge_kind == kind::WAKE, PledgeError::InvalidTarget);
    require!((window_sec as u64) <= day_duration_sec, PledgeError::InvalidSchedule);

    let now = Clock::get()?.unix_timestamp;
    // 0 means "now"; otherwise a start up to two days ahead (never in the past).
    let start = if start_at == 0 { now } else { start_at };
    require!(start >= now && start <= now + MAX_START_DELAY, PledgeError::InvalidSchedule);

    let session = ctx.accounts.clock_in_authority.key();
    let commitment = &mut ctx.accounts.commitment;
    commitment.authority = ctx.accounts.user.key();
    commitment.clock_in_authority = if session == System::id() {
        Pubkey::default()
    } else {
        session
    };
    commitment.token_mint = ctx.accounts.token_mint.key();
    commitment.vault = ctx.accounts.vault.key();
    commitment.target_steps = target_steps;
    commitment.total_days = total_days;
    commitment.completed_days = 0;
    commitment.day_duration_sec = day_duration_sec;
    commitment.start_timestamp = start;
    commitment.total_amount = amount;
    commitment.settled = false;
    commitment.clocked_in_bitmap = 0;
    commitment.bump = ctx.bumps.commitment;
    commitment.vault_bump = ctx.bumps.vault;
    commitment.commitment_id = commitment_id;
    commitment.kind = pledge_kind;
    commitment.window_sec = window_sec;

    let profile = &mut ctx.accounts.profile;
    if profile.authority == Pubkey::default() {
        profile.authority = ctx.accounts.user.key();
        profile.bump = ctx.bumps.profile;
    }
    profile.pledges_started = profile.pledges_started.saturating_add(1);
    profile.total_staked = profile.total_staked.saturating_add(amount);

    token::transfer(
        CpiContext::new(
            ctx.accounts.token_program.to_account_info(),
            Transfer {
                from: ctx.accounts.user_token_account.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.user.to_account_info(),
            },
        ),
        amount,
    )?;

    emit!(CommitmentCreatedEvent {
        commitment: commitment.key(),
        authority: commitment.authority,
        token_mint: commitment.token_mint,
        total_amount: amount,
        target_steps,
        total_days,
        day_duration_sec,
        start_timestamp: start,
        kind: pledge_kind,
    });

    Ok(())
}
