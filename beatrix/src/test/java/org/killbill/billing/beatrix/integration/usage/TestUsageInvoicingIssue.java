/*
 * Copyright 2020-2023 Equinix, Inc
 * Copyright 2014-2023 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration.usage;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.joda.time.LocalDate;
import org.joda.time.Period;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.integration.TestIntegrationBase;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.entitlement.api.DefaultEntitlementSpecifier;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceStatus;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.util.config.definition.InvoiceConfig;
import org.killbill.billing.util.features.KillbillFeatures;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TestUsageInvoicingIssue extends TestIntegrationBase {

    @Override
    protected KillbillConfigSource getConfigSource(final Map<String, String> extraProperties) {
        final Map<String, String> allExtraProperties = new HashMap<String, String>(extraProperties);
        allExtraProperties.putAll(DEFAULT_BEATRIX_PROPERTIES);
        allExtraProperties.put(KillbillFeatures.PROP_FEATURE_INVOICE_OPTIMIZATION, "true");
        return getConfigSource(null, allExtraProperties);
    }

    @BeforeMethod(groups = "slow")
    public void beforeMethod() throws Exception {
        super.beforeMethod();

        invoiceConfig.setMaxInvoiceLimit(new Period("P1M"));
        invoiceConfig.setMaxRawUsagePreviousPeriod(0);
    }

    /*
    1.  Set clock to 8/28, create new account, create new subscription w/ start date 8/28 billing in arrear w/ unit pricing, BCD = 1
2.  Record some usage for period 8/28-8/31
3.  Generate invoice w/ target date 9/1 and commit
4.  Set clock to 9/21
5.  Generate invoice w/ target date 10/1 and see the error!
     */
    @Test(groups = "slow")
    public void testWithUsageInvoicingIssue() throws Exception {
        clock.setDay(new LocalDate(2023, 8, 28));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);
        add_AUTO_INVOICING_DRAFT_Tag(account.getId(), ObjectType.ACCOUNT);


        // CREATE SUBSCRIPTION
        busHandler.pushExpectedEvents(NextEvent.CREATE, NextEvent.BLOCK, NextEvent.BCD_CHANGE, NextEvent.NULL_INVOICE);
        final PlanPhaseSpecifier spec = new PlanPhaseSpecifier("trebuchet-usage-in-arrear");
        final UUID subscriptionId = entitlementApi.createBaseEntitlement(account.getId(), new DefaultEntitlementSpecifier(spec, 1, null, null, null), "bundleKey", null, null, false, true, Collections.emptyList(), callContext);
        assertListenerStatus();

        //Record usage for 2023-08-29
        recordUsageData(subscriptionId, "tracking-1", "stones", new LocalDate(2023, 8, 29), BigDecimal.valueOf(50L), callContext);

        //generate invoice with date 2023-09-01 and commit it
        Invoice invoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), new LocalDate(2023, 9, 1), Collections.emptyList(), callContext);
        assertEquals(invoice.getStatus(), InvoiceStatus.DRAFT);
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        invoiceUserApi.commitInvoice(invoice.getId(), callContext);
        assertListenerStatus();

        //set date to 2023-09-21
        clock.setDay(new LocalDate(2023, 9, 21));

        //generate invoice with date 2023-10-01
        busHandler.pushExpectedEvents(NextEvent.NULL_INVOICE); // no usage recorded so null invoice
        invoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), new LocalDate(2023, 10, 1), Collections.emptyList(), callContext);
        assertListenerStatus();

        Thread.sleep(1000 * 3600);
    }

}
