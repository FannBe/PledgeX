use anchor_lang::prelude::*;
use anchor_spl::token::{self, Mint, Token, TokenAccount, Transfer};

use crate::errors::PledgeError;
use crate::state::{Commitment, CommitmentCreatedEvent, COMMITMENT_SEED, VAULT_SEED};

pub const MIN_DAY_SEC: u64 = 60;
pub const MAX_DAY_SEC: u64 = 7 * 86_400;

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
        mut,
        token::mint = token_mint,
        token::authority = user,
    )]
    pub user_token_account: Account<'info, TokenAccount>,

    pub token_mint: Account<'info, Mint>,

    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
    pub rent: Sysvar<'info, Rent>,
}

pub fn handle_create_commitment(
    ctx: Context<CreateCommitment>,
    commitment_id: u64,
    target_steps: u32,
    total_days: u8,
    day_duration_sec: u64,
    amount: u64,
) -> Result<()> {
    require!((1..=64).contains(&total_days), PledgeError::InvalidTotalDays);
    require!(
        (MIN_DAY_SEC..=MAX_DAY_SEC).contains(&day_duration_sec),
        PledgeError::InvalidDuration
    );
    require!(amount > 0, PledgeError::InvalidAmount);
    require!(target_steps > 0, PledgeError::InvalidTarget);

    let now = Clock::get()?.unix_timestamp;
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
    commitment.start_timestamp = now;
    commitment.total_amount = amount;
    commitment.settled = false;
    commitment.clocked_in_bitmap = 0;
    commitment.bump = ctx.bumps.commitment;
    commitment.vault_bump = ctx.bumps.vault;
    commitment.commitment_id = commitment_id;

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
        start_timestamp: now,
    });

    Ok(())
}
