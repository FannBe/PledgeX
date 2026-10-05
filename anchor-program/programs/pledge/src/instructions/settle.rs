use anchor_lang::prelude::*;
use anchor_spl::token::{self, Burn, CloseAccount, Mint, Token, TokenAccount, Transfer};

use crate::errors::PledgeError;
use crate::state::{Commitment, SettledEvent, VAULT_SEED};

#[derive(Accounts)]
pub struct Settle<'info> {
    /// Anyone may settle (the owner, or a crank); they only pay the fee.
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

    pub token_program: Program<'info, Token>,
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
