use anchor_lang::prelude::*;
use anchor_spl::token::{self, Burn, CloseAccount, Mint, Token, TokenAccount, Transfer};

use crate::errors::PledgeError;
use crate::state::{Commitment, Profile, SettledEvent, MIN_REAL_DAY_SEC, PROFILE_SEED, VAULT_SEED};

#[derive(Accounts)]
pub struct Settle<'info> {
    /// Anyone may settle (the owner, or a crank); they pay the fee, and the profile's
    /// rent for a commitment made before profiles existed.
    #[account(mut)]
    pub caller: Signer<'info>,

    /// CHECK: the owner; receives the refund's rent and the commitment's rent.
    #[account(mut, address = commitment.authority)]
    pub user: SystemAccount<'info>,

    #[account(
        mut,
        close = user,
        constraint = !commitment.settled @ PledgeError::AlreadySettled,
        has_one = vault,
        has_one = token_mint,
    )]
    pub commitment: Account<'info, Commitment>,

    #[account(
        mut,
        seeds = [VAULT_SEED, commitment.key().as_ref()],
        bump = commitment.vault_bump,
    )]
    pub vault: Account<'info, TokenAccount>,

    #[account(
        mut,
        token::mint = token_mint,
        token::authority = user,
    )]
    pub user_token_account: Account<'info, TokenAccount>,

    #[account(mut)]
    pub token_mint: Account<'info, Mint>,

    #[account(
        init_if_needed,
        payer = caller,
        space = Profile::LEN,
        seeds = [PROFILE_SEED, commitment.authority.as_ref()],
        bump
    )]
    pub profile: Account<'info, Profile>,

    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_settle(ctx: Context<Settle>) -> Result<()> {
    let commitment = &mut ctx.accounts.commitment;
    let now = Clock::get()?.unix_timestamp;

    let total_duration = (commitment.total_days as i64)
        .checked_mul(commitment.day_duration_sec as i64)
        .ok_or(PledgeError::MathOverflow)?;
    let end_timestamp = commitment
        .start_timestamp
        .checked_add(total_duration)
        .ok_or(PledgeError::MathOverflow)?;
    require!(now >= end_timestamp, PledgeError::CommitmentNotEnded);

    // Refund = total * completed / total_days (u128 so a large stake cannot overflow);
    // burn is the exact remainder, so the vault always ends at zero and can be closed.
    let total_amount = commitment.total_amount;
    let refund_amount = ((total_amount as u128) * (commitment.completed_days as u128)
        / (commitment.total_days as u128)) as u64;
    let burn_amount = total_amount
        .checked_sub(refund_amount)
        .ok_or(PledgeError::MathOverflow)?;

    let commitment_key = commitment.key();
    let vault_seeds: &[&[u8]] = &[VAULT_SEED, commitment_key.as_ref(), &[commitment.vault_bump]];
    let signer = &[vault_seeds];
    let token_program = ctx.accounts.token_program.to_account_info();

    if refund_amount > 0 {
        token::transfer(
            CpiContext::new_with_signer(
                token_program.clone(),
                Transfer {
                    from: ctx.accounts.vault.to_account_info(),
                    to: ctx.accounts.user_token_account.to_account_info(),
                    authority: ctx.accounts.vault.to_account_info(),
                },
                signer,
            ),
            refund_amount,
        )?;
    }

    // Missed days are destroyed: nobody, including the developers, receives them.
    if burn_amount > 0 {
        token::burn(
            CpiContext::new_with_signer(
                token_program.clone(),
                Burn {
                    mint: ctx.accounts.token_mint.to_account_info(),
                    from: ctx.accounts.vault.to_account_info(),
                    authority: ctx.accounts.vault.to_account_info(),
                },
                signer,
            ),
            burn_amount,
        )?;
    }

    token::close_account(CpiContext::new_with_signer(
        token_program,
        CloseAccount {
            account: ctx.accounts.vault.to_account_info(),
            destination: ctx.accounts.user.to_account_info(),
            authority: ctx.accounts.vault.to_account_info(),
        },
        signer,
    ))?;

    commitment.settled = true;

    let kept = commitment.completed_days as u32;
    let missed = (commitment.total_days as u32).saturating_sub(kept);
    let mut best = 0u8;
    let mut run = 0u8;
    for day in 0..commitment.total_days {
        if (commitment.kept_bitmap >> day) & 1 == 1 {
            run += 1;
            best = best.max(run);
        } else {
            run = 0;
        }
    }
    let profile = &mut ctx.accounts.profile;
    if profile.authority == Pubkey::default() {
        profile.authority = commitment.authority;
        profile.bump = ctx.bumps.profile;
        profile.pledges_started = 1;
        profile.total_staked = total_amount;
    }
    profile.pledges_settled = profile.pledges_settled.saturating_add(1);
    profile.days_kept = profile.days_kept.saturating_add(kept);
    profile.days_missed = profile.days_missed.saturating_add(missed);
    profile.total_returned = profile.total_returned.saturating_add(refund_amount);
    profile.total_burned = profile.total_burned.saturating_add(burn_amount);
    // Badges and rank only from real days; a demo pledge still moves the money.
    if commitment.day_duration_sec >= MIN_REAL_DAY_SEC {
        profile.real_days_kept = profile.real_days_kept.saturating_add(kept);
        profile.best_streak = profile.best_streak.max(best);
        if missed == 0 {
            profile.perfect_pledges = profile.perfect_pledges.saturating_add(1);
            profile.perfect_kinds |= 1u8 << commitment.kind.min(7);
        }
    }

    emit!(SettledEvent {
        commitment: commitment_key,
        user: commitment.authority,
        refunded_amount: refund_amount,
        burned_amount: burn_amount,
        completed_days: commitment.completed_days,
        total_days: commitment.total_days,
    });

    Ok(())
}
