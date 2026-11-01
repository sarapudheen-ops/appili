package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.domain.profile.pack.Tier

/**
 * The brand picker: one prominent universal action, then the catalogue.
 *
 * ### Why the top action is first
 * Most OBD-II work needs no brand at all. Putting "Any Vehicle — Generic OBD-II Only" above the list
 * means the fastest path is also the honest one: it never pretends a profile exists.
 *
 * ### Why the capability labels are text
 * `AGENTS.md` Rule 2 and `docs/SAFETY_POLICY.md` forbid a control that cannot act. A button labelled
 * "Active Test" on an unverified variant would be exactly that — it would look like an offer and do
 * nothing, or worse, look like it had. So capabilities that are unavailable are rendered as labels
 * with their reason ("Unavailable — Unverified Profile", "Simulator Only"), and the screen contains no
 * dead clickable control at all.
 */
@Composable
fun BrandPickerScreen(
    state: BrandPickerState,
    onEvent: (BrandPickerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onEvent(BrandPickerEvent.AnyVehicle) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = BrandPickerModel.ANY_VEHICLE_LABEL,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = BrandPickerModel.ANY_VEHICLE_DETAIL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.genericOnly) {
            GenericOnlyCard(onBackToCatalogue = { onEvent(BrandPickerEvent.BackToCatalogue) })
            return@Column
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
            )
        }

        val selected = state.selectedBrand
        if (selected == null) {
            Text(
                text = "Brands",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Selecting a brand shows what its pack declares. It does not widen what may be " +
                    "sent: the scan stays L1 generic OBD-II and the gate refuses everything else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.brands.forEach { brand ->
                BrandCard(brand = brand, onOpen = { onEvent(BrandPickerEvent.SelectBrand(brand.brandId)) })
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onEvent(BrandPickerEvent.BackToCatalogue) }) {
                    Text("All brands")
                }
                Text(
                    text = selected.brandName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TierBadge(tier = selected.tier)
            Text(
                text = "Variants: ${selected.variantCount} · Verified: ${selected.verifiedVariantCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected.families.isEmpty()) {
                Text(
                    text = "No vehicles declared for this brand yet — generic OBD-II only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selected.families.forEach { family ->
                FamilyCard(
                    family = family,
                    selectedVariantId = state.selectedVariantId,
                    onSelectVariant = { variantId -> onEvent(BrandPickerEvent.SelectVariant(variantId)) },
                )
            }
        }
    }
}

@Composable
private fun GenericOnlyCard(onBackToCatalogue: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Generic OBD-II only",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "No brand profile is selected. The scan runs the universal SAE J1979 reads only " +
                    "(modes 01, 03, 07, 09) and no OEM operation is reachable from this route.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onBackToCatalogue) {
                Text("Choose a vehicle instead")
            }
        }
    }
}

@Composable
private fun BrandCard(brand: PickerBrand, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = brand.brandName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TierBadge(tier = brand.tier)
            if (brand.variantCount == 0) {
                // No vehicles declared yet. Said plainly, and with no button: a "Show families"
                // control that opens an empty list would be a dead clickable control.
                Text(
                    text = "No vehicles declared for this brand yet — generic OBD-II only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Variants: ${brand.variantCount} · Verified: ${brand.verifiedVariantCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpen) {
                    Text("Show families")
                }
            }
        }
    }
}

@Composable
private fun FamilyCard(
    family: PickerFamilyEntry,
    selectedVariantId: String?,
    onSelectVariant: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = family.familyName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            family.variants.forEach { variant ->
                VariantRow(
                    variant = variant,
                    expanded = variant.variantId == selectedVariantId,
                    onSelect = { onSelectVariant(variant.variantId) },
                )
            }
        }
    }
}

@Composable
private fun VariantRow(variant: PickerVariantEntry, expanded: Boolean, onSelect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = variant.summary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = variant.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (variant.verified) MaterialTheme.colorScheme.primary else WarningAmber,
        )

        // Informational labels, never controls: nothing here can be tapped, because nothing here is
        // permitted on this variant. See the class comment.
        variant.capabilities.forEach { capability ->
            Text(
                text = "${capability.label} — ${capability.status}",
                style = MaterialTheme.typography.bodySmall,
                color = if (capability.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (expanded) {
            variant.ecus.forEach { ecu ->
                Text(
                    text = "ECU ${ecu.name} · part ${ecu.partNumber} · ${ecu.network} · " +
                        "${ecu.addressing} · ${ecu.statusLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Variant ${variant.variantId} (${variant.brandId}/${variant.familyId})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(onClick = onSelect) {
                Text("Details")
            }
        }
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    Text(
        text = BrandPickerModel.tierLabel(tier),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = tierColor(tier),
    )
}

/** Badge colours: a tier is a claim, and a verified one should look different from an unverified one. */
private fun tierColor(tier: Tier): Color = when (tier) {
    Tier.T1_FULL_ENHANCED -> Color(0xFF2E7D32)
    Tier.T2_PARTIAL_ENHANCED -> Color(0xFF1565C0)
    Tier.T3_GENERIC_ONLY -> Color(0xFF6A1B9A)
    Tier.T4_UNVERIFIED -> WarningAmber
}

@Preview(showBackground = true)
@Composable
private fun BrandPickerScreenPreview() {
    AppTheme {
        BrandPickerScreen(
            state = BrandPickerState(message = BrandPickerModel.NO_CATALOGUE_MESSAGE),
            onEvent = {},
        )
    }
}
