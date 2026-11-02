package com.example.orderrisk.pricing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingCatalogTest {

    @Test
    void customerProfileIsDeterministic() {
        assertThat(PricingCatalog.customerProfile("C-1001"))
                .isEqualTo(PricingCatalog.customerProfile("C-1001"));
    }

    @Test
    void priceSnapshotsAreReturnedInRequestOrder() {
        var snapshots = PricingCatalog.priceSnapshots(List.of("SKU-4002", "SKU-1001"));

        assertThat(snapshots).extracting(PricingCatalog.PriceSnapshot::sku).containsExactly("SKU-4002", "SKU-1001");
        assertThat(snapshots.get(0).unitPrice()).isEqualByComparingTo("699.00");
    }

    @Test
    void unknownSkuIsRejected() {
        assertThatThrownBy(() -> PricingCatalog.priceSnapshots(List.of("SKU-0000")))
                .isInstanceOf(PricingCatalog.UnknownSkuException.class);
    }

    @Test
    void malformedCustomerIdIsRejected() {
        assertThatThrownBy(() -> PricingCatalog.customerProfile("bob"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void benchmarkCustomerProfileMatchesTheVersionedRiskInput() {
        PricingCatalog.CustomerProfile profile = PricingCatalog.customerProfile("C-2026");
        assertThat(profile.accountAgeDays()).isEqualTo(316);
        assertThat(profile.previousOrders()).isEqualTo(22);
        assertThat(profile.chargebacks()).isZero();
        assertThat(profile.averageOrderValue()).isEqualByComparingTo("377.00");
        assertThat(profile.homeCountry()).isEqualTo("SE");
    }

}
