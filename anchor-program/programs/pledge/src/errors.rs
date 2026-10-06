use anchor_lang::prelude::*;

#[error_code]
pub enum PledgeError {
    #[msg("Reported step count is below the daily target.")]
    TargetNotMet,

    #[msg("You have already clocked in for this day.")]
    DayAlreadyClockedIn,

    #[msg("The current time does not match this day's clock-in window.")]
    InvalidDayWindow,

    #[msg("Commitment duration has not ended yet. Cannot settle early.")]
    CommitmentNotEnded,

    #[msg("This commitment has already been settled.")]
    AlreadySettled,

    #[msg("Signer is not authorized to clock in (must be user or local session key).")]
    UnauthorizedClockIn,

    #[msg("Total days must be between 1 and 64.")]
    InvalidTotalDays,

    #[msg("Day duration must be between 60 seconds and 7 days.")]
    InvalidDuration,

    #[msg("Arithmetic overflow occurred.")]
    MathOverflow,

    #[msg("Stake amount must be greater than zero.")]
    InvalidAmount,

    #[msg("Daily step target must be greater than zero.")]
    InvalidTarget,

    #[msg("This wallet already holds enough test SKR; the faucet refills below 5,000.")]
    FaucetBalanceTooHigh,

    #[msg("Unknown habit kind.")]
    InvalidKind,

    #[msg("The start must be within the next two days and the window inside one day.")]
    InvalidSchedule,

    #[msg("Today's value is over your limit, so this day cannot be clocked in.")]
    LimitExceeded,

    #[msg("There is no badge with that number.")]
    InvalidBadge,

    #[msg("This badge is not earned yet. Badges come from real pledges (days of an hour or more).")]
    BadgeNotEarned,
}
