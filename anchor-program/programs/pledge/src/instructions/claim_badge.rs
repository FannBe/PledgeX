use anchor_lang::prelude::*;
use anchor_lang::solana_program::{instruction::{AccountMeta, Instruction}, program::invoke};
use anchor_lang::system_program::{create_account, CreateAccount};
use anchor_spl::token_2022::spl_token_2022::{extension::ExtensionType, instruction::AuthorityType, state::Mint as MintState};
use anchor_spl::token_2022::{initialize_mint2, mint_to, set_authority, InitializeMint2, MintTo, SetAuthority};
use anchor_spl::token_2022_extensions::spl_pod::optional_keys::OptionalNonZeroPubkey;
use anchor_spl::token_2022_extensions::spl_token_metadata_interface::state::TokenMetadata;
use anchor_spl::token_2022_extensions::{
    metadata_pointer_initialize, non_transferable_mint_initialize, token_metadata_initialize,
    MetadataPointerInitialize, NonTransferableMintInitialize, TokenMetadataInitialize,
};
use anchor_spl::token_interface::Token2022;

use crate::errors::PledgeError;
use crate::state::{kind, BadgeClaimedEvent, Profile, BADGE_AUTH_SEED, BADGE_SEED, PROFILE_SEED};

pub const ASSOCIATED_TOKEN_PROGRAM: Pubkey = pubkey!("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

/// The badges, in id order: (name, what earns it).
pub const BADGES: [&str; 6] = ["First Pledge", "10K Pioneer", "6 AM Club Hero", "Digital Detox", "Week Streak", "Iron Will"];

/// Whether `profile` has earned badge `id`; only real pledges count towards 1..=5.
pub fn earned(profile: &Profile, id: u8) -> bool {
    match id {
        0 => profile.pledges_started >= 1,
        1 => profile.perfect_kinds & (1 << kind::STEPS) != 0,
        2 => profile.perfect_kinds & (1 << kind::WAKE) != 0,
        3 => profile.perfect_kinds & (1 << kind::SCREEN) != 0,
        4 => profile.best_streak >= 7,
        5 => profile.perfect_pledges >= 3,
        _ => false,
    }
}

/// Mints the caller one non-transferable Token-2022 NFT for an earned badge: a mint PDA
/// per (wallet, badge), so each badge exists once per wallet; supply 1, then the mint
/// authority is removed. It shows in any wallet that reads Token-2022 metadata.
#[derive(Accounts)]
#[instruction(badge: u8)]
pub struct ClaimBadge<'info> {
    #[account(mut)]
    pub user: Signer<'info>,

    #[account(seeds = [PROFILE_SEED, user.key().as_ref()], bump = profile.bump, constraint = profile.authority == user.key())]
    pub profile: Account<'info, Profile>,

    /// CHECK: created here as a Token-2022 mint; the seeds make it one per wallet and badge.
    #[account(mut, seeds = [BADGE_SEED, user.key().as_ref(), &[badge]], bump)]
    pub badge_mint: UncheckedAccount<'info>,

    /// CHECK: PDA that is the badge mints' mint and metadata authority; signs by seeds.
    #[account(seeds = [BADGE_AUTH_SEED], bump)]
    pub badge_authority: UncheckedAccount<'info>,

    /// CHECK: the user's associated token account for the badge, checked against its
    /// derivation and created here once the mint exists.
    #[account(mut)]
    pub user_badge_account: UncheckedAccount<'info>,

    pub token_program: Program<'info, Token2022>,
    /// CHECK: the associated token program.
    #[account(address = ASSOCIATED_TOKEN_PROGRAM)]
    pub associated_token_program: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

pub fn handle_claim_badge(ctx: Context<ClaimBadge>, badge: u8) -> Result<()> {
    require!((badge as usize) < BADGES.len(), PledgeError::InvalidBadge);
    require!(earned(&ctx.accounts.profile, badge), PledgeError::BadgeNotEarned);

    let user = ctx.accounts.user.key();
    let mint = ctx.accounts.badge_mint.key();
    let token_program = ctx.accounts.token_program.key();
    let (ata, _) = Pubkey::find_program_address(&[user.as_ref(), token_program.as_ref(), mint.as_ref()], &ASSOCIATED_TOKEN_PROGRAM);
    require_keys_eq!(ata, ctx.accounts.user_badge_account.key(), PledgeError::InvalidBadge);

    let mint_bump = ctx.bumps.badge_mint;
    let auth_bump = ctx.bumps.badge_authority;
    let mint_seeds: &[&[u8]] = &[BADGE_SEED, user.as_ref(), &[badge], &[mint_bump]];
    let auth_seeds: &[&[u8]] = &[BADGE_AUTH_SEED, &[auth_bump]];
    let authority = ctx.accounts.badge_authority.key();

    let name = format!("PledgeX · {}", BADGES[badge as usize]);
    let symbol = "PLDGX".to_string();
    let uri = format!("https://fannbe.github.io/PledgeX/badges/{}.json", badge);
    let metadata = TokenMetadata {
        update_authority: OptionalNonZeroPubkey::try_from(Some(authority))?,
        mint,
        name: name.clone(),
        symbol: symbol.clone(),
        uri: uri.clone(),
        additional_metadata: vec![],
    };

    // The account holds the mint and two extensions; the metadata is appended by the
    // token program later, so the rent for it is paid up front.
    let space = ExtensionType::try_calculate_account_len::<MintState>(&[ExtensionType::NonTransferable, ExtensionType::MetadataPointer])?;
    let lamports = Rent::get()?.minimum_balance(space + metadata.tlv_size_of()?);
    create_account(
        CpiContext::new_with_signer(
            ctx.accounts.system_program.to_account_info(),
            CreateAccount { from: ctx.accounts.user.to_account_info(), to: ctx.accounts.badge_mint.to_account_info() },
            &[mint_seeds],
        ),
        lamports,
        space as u64,
        &token_program,
    )?;

    let tp = ctx.accounts.token_program.to_account_info();
    let mint_info = ctx.accounts.badge_mint.to_account_info();
    let auth_info = ctx.accounts.badge_authority.to_account_info();

    non_transferable_mint_initialize(CpiContext::new(tp.clone(), NonTransferableMintInitialize {
        token_program_id: tp.clone(),
        mint: mint_info.clone(),
    }))?;
    metadata_pointer_initialize(
        CpiContext::new(tp.clone(), MetadataPointerInitialize { token_program_id: tp.clone(), mint: mint_info.clone() }),
        Some(authority),
        Some(mint),
    )?;
    initialize_mint2(CpiContext::new(tp.clone(), InitializeMint2 { mint: mint_info.clone() }), 0, &authority, None)?;
    token_metadata_initialize(
        CpiContext::new_with_signer(
            tp.clone(),
            TokenMetadataInitialize {
                program_id: tp.clone(),
                metadata: mint_info.clone(),
                update_authority: auth_info.clone(),
                mint_authority: auth_info.clone(),
                mint: mint_info.clone(),
            },
            &[auth_seeds],
        ),
        name,
        symbol,
        uri,
    )?;

    // The holder's account, through the associated token program (CreateIdempotent).
    invoke(
        &Instruction {
            program_id: ASSOCIATED_TOKEN_PROGRAM,
            accounts: vec![
                AccountMeta::new(user, true),
                AccountMeta::new(ata, false),
                AccountMeta::new_readonly(user, false),
                AccountMeta::new_readonly(mint, false),
                AccountMeta::new_readonly(System::id(), false),
                AccountMeta::new_readonly(token_program, false),
            ],
            data: vec![1],
        },
        &[
            ctx.accounts.user.to_account_info(),
            ctx.accounts.user_badge_account.to_account_info(),
            ctx.accounts.user.to_account_info(),
            mint_info.clone(),
            ctx.accounts.system_program.to_account_info(),
            tp.clone(),
            ctx.accounts.associated_token_program.to_account_info(),
        ],
    )?;

    mint_to(
        CpiContext::new_with_signer(
            tp.clone(),
            MintTo { mint: mint_info.clone(), to: ctx.accounts.user_badge_account.to_account_info(), authority: auth_info.clone() },
            &[auth_seeds],
        ),
        1,
    )?;
    // Supply is one, forever.
    set_authority(
        CpiContext::new_with_signer(
            tp,
            SetAuthority { current_authority: auth_info, account_or_mint: mint_info },
            &[auth_seeds],
        ),
        AuthorityType::MintTokens,
        None,
    )?;

    emit!(BadgeClaimedEvent { user, badge, mint });
    Ok(())
}
