# Nibbl Google Play Monetization

Package name: `au.z2hs.nibbl`

## Products to create

Create these in Google Play Console before testing checkout:

| Product ID | Type | Suggested name | Suggested price |
| --- | --- | --- | --- |
| `nibbl_plus_lifetime` | One-time product | Nibbl Plus | US$4.99 one-time |
| `nibbl_pro_monthly` | Subscription | Nibbl Pro Monthly | US$1.99/month |
| `nibbl_pro_yearly` | Subscription | Nibbl Pro Yearly | US$14.99/year |

## Suggested benefits

Free:
- Basic diary logs
- Basic categories
- Friend profile links
- Public day sharing
- 60 background removals/month
- 3 custom food + drink categories

Nibbl Plus:
- Unlimited logs
- Unlimited custom food + drink categories
- Extra themes, icons, and stickers
- Calendar recap cards
- Advanced filters

Nibbl Pro:
- Cloud sync and backup
- Friend albums
- Background-removal credits
- Higher background-removal limits
- Monthly/yearly recap exports
- Cross-device restore
- Priority future features

## Play Console setup

1. Create the app in Google Play Console with package name `au.z2hs.nibbl`.
2. Upload an internal testing build.
3. Go to **Monetize with Play > Products > In-app products** and create `nibbl_plus_lifetime`.
4. Go to **Monetize with Play > Products > Subscriptions** and create `nibbl_pro_monthly` and `nibbl_pro_yearly`.
5. Activate the products and add testers to the internal testing track.
6. Install the app from the Play internal test link. Sideloaded debug builds can connect to Billing, but products generally will not resolve until the package/version is known to Play and installed through a test track.

## Current app behavior

The Settings screen shows a Nibbl Plus + Pro card. It queries Google Play Billing, shows live prices when products are available, launches checkout, acknowledges purchases, and restores local entitlements.

Local entitlement flags are cached in `app_settings.json`:
- `plusUnlocked`
- `proActive`
- `lastPurchaseSyncMillis`
- `backgroundRemovalMonth`
- `backgroundRemovalsThisMonth`

For public release, verify purchase tokens server-side before trusting Pro-only backend features.
