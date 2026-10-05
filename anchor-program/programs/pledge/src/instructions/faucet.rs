use anchor_lang::prelude::*;
use anchor_spl::token::{self, Mint, MintTo, Token, TokenAccount};

use crate::errors::PledgeError;
use crate::state::{FaucetEvent, FAUCET_AMOUNT, FAUCET_CAP, FAUCET_SEED, SKR_MINT};

/// The client creates the caller's token account (idempotent ATA instruction) in the
/// same transaction, so the program needs no associated-token dependency.
#[derive(Accounts)]
pub struct Faucet<'info> {
    pub user: Signer<'info>,

    #[account(mut, address = SKR_MINT)]
    pub token_mint: Account<'info, Mint>,

    /// CHECK: PDA that is the mint authority of the test SKR mint; signs by seeds.
    #[account(seeds = [FAUCET_SEED], bump)]
    pub faucet_authority: UncheckedAccount<'info>,

    #[account(
        mut,
        token::mint = token_mint,
        token::authority = user,
    )]
    pub user_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

pub fn handle_faucet(ctx: Context<Faucet>) -> Result<()> {
    require!(
        ctx.accounts.user_token_account.amount < FAUCET_CAP,
        PledgeError::FaucetBalanceTooHigh
    );

    let bump = ctx.bumps.faucet_authority;
    let seeds: &[&[u8]] = &[FAUCET_SEED, &[bump]];
    let signer = &[seeds];
    token::mint_to(
        CpiContext::new_with_signer(
            ctx.accounts.token_program.to_account_info(),
            MintTo {
                mint: ctx.accounts.token_mint.to_account_info(),
                to: ctx.accounts.user_token_account.to_account_info(),
                authority: ctx.accounts.faucet_authority.to_account_info(),
            },
            signer,
        ),
        FAUCET_AMOUNT,
    )?;

    emit!(FaucetEvent {
        user: ctx.accounts.user.key(),
        amount: FAUCET_AMOUNT,
    });
    Ok(())
}
