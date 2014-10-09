/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.TestUtils;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;
import org.elasticsearch.license.core.LicenseUtils;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseAction;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseRequest;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseResponse;
import org.elasticsearch.license.plugin.action.delete.TransportDeleteLicenseAction;
import org.elasticsearch.license.plugin.action.get.GetLicenseRequest;
import org.elasticsearch.license.plugin.action.get.GetLicenseResponse;
import org.elasticsearch.license.plugin.action.get.TransportGetLicenseAction;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequest;
import org.elasticsearch.license.plugin.action.put.PutLicenseResponse;
import org.elasticsearch.license.plugin.action.put.TransportPutLicenseAction;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.*;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@ClusterScope(scope = SUITE, numDataNodes = 10)
public class LicenseTransportTests extends ElasticsearchIntegrationTest {

    private static String pubKeyPath = null;
    private static String priKeyPath = null;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("plugins.load_classpath_plugins", false)
                .put("plugin.types", LicensePlugin.class.getName())
                .build();
    }

    @BeforeClass
    public static void setup() throws IOException, URISyntaxException {
        priKeyPath = Paths.get(LicenseTransportTests.class.getResource("/org.elasticsearch.license.plugin/test_pri.key").toURI()).toAbsolutePath().toString();
        pubKeyPath = Paths.get(LicenseTransportTests.class.getResource("/org.elasticsearch.license.plugin/test_pub.key").toURI()).toAbsolutePath().toString();
    }

    /*
     * TODO:
     *  - add more delete tests
     *  - add put invalid licenses tests
     *  - add multiple licenses of the same feature tests
     */

    @Test
    public void testEmptyGetLicense() throws Exception {
        final ActionFuture<DeleteLicenseResponse> deleteFuture = licenseDeleteAction().execute(new DeleteLicenseRequest("marvel", "shield"));
        final DeleteLicenseResponse deleteLicenseResponse = deleteFuture.get();
        assertTrue(deleteLicenseResponse.isAcknowledged());

        final ActionFuture<GetLicenseResponse> getLicenseFuture = licenseGetAction().execute(new GetLicenseRequest());

        final GetLicenseResponse getLicenseResponse = getLicenseFuture.get();

        assertThat("expected 0 licenses; but got: " + getLicenseResponse.licenses(), getLicenseResponse.licenses().licenses().size(), equalTo(0));
    }

    @Test
    public void testPutLicense() throws ParseException, ExecutionException, InterruptedException, IOException {

        Map<ESLicenses.FeatureType, TestUtils.FeatureAttributes> map = new HashMap<>();
        TestUtils.FeatureAttributes featureAttributes =
                new TestUtils.FeatureAttributes("shield", "subscription", "platinum", "foo bar Inc.", "elasticsearch", 2, "2014-12-13", "2015-12-13");
        map.put(ESLicenses.FeatureType.SHIELD, featureAttributes);
        String licenseString = TestUtils.generateESLicenses(map);
        String licenseOutput = TestUtils.runLicenseGenerationTool(licenseString, pubKeyPath, priKeyPath);

        PutLicenseRequest putLicenseRequest = new PutLicenseRequest();
        //putLicenseRequest.license(licenseString);
        final ESLicenses putLicenses = LicenseUtils.readLicensesFromString(licenseOutput);
        putLicenseRequest.license(putLicenses);
        //LicenseUtils.printLicense(putLicenses);
        ensureGreen();

        final ActionFuture<PutLicenseResponse> putLicenseFuture = licensePutAction().execute(putLicenseRequest);

        final PutLicenseResponse putLicenseResponse = putLicenseFuture.get();

        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));

        ActionFuture<GetLicenseResponse> getLicenseFuture = licenseGetAction().execute(new GetLicenseRequest());

        GetLicenseResponse getLicenseResponse = getLicenseFuture.get();

        assertThat(getLicenseResponse.licenses(), notNullValue());

        //LicenseUtils.printLicense(getLicenseResponse.licenses());
        assertTrue(isSame(putLicenses, getLicenseResponse.licenses()));


        final ActionFuture<DeleteLicenseResponse> deleteFuture = licenseDeleteAction().execute(new DeleteLicenseRequest("marvel", "shield"));
        final DeleteLicenseResponse deleteLicenseResponse = deleteFuture.get();
        assertTrue(deleteLicenseResponse.isAcknowledged());

        getLicenseResponse = licenseGetAction().execute(new GetLicenseRequest()).get();
        assertTrue(isSame(getLicenseResponse.licenses(), LicenseBuilders.licensesBuilder().build()));
    }

    public TransportGetLicenseAction licenseGetAction() {
        final InternalTestCluster clients = internalCluster();
        return clients.getInstance(TransportGetLicenseAction.class);
    }

    public TransportPutLicenseAction licensePutAction() {
        final InternalTestCluster clients = internalCluster();
        return clients.getInstance(TransportPutLicenseAction.class);
    }

    public TransportDeleteLicenseAction licenseDeleteAction() {
        final InternalTestCluster clients = internalCluster();
        return clients.getInstance(TransportDeleteLicenseAction.class);
    }


    //TODO: convert to asserts
    public static boolean isSame(ESLicenses firstLicenses, ESLicenses secondLicenses) {

        // we do the build to make sure we weed out any expired licenses
        final ESLicenses licenses1 = LicenseBuilders.licensesBuilder().licenses(firstLicenses).build();
        final ESLicenses licenses2 = LicenseBuilders.licensesBuilder().licenses(secondLicenses).build();

        // check if the effective licenses have the same feature set
        if (!licenses1.features().equals(licenses2.features())) {
            return false;
        }

        // for every feature license, check if all the attributes are the same
        for (ESLicenses.FeatureType featureType : licenses1.features()) {
            ESLicenses.ESLicense license1 = licenses1.get(featureType);
            ESLicenses.ESLicense license2 = licenses2.get(featureType);

            if (!license1.uid().equals(license2.uid())
                    || !license1.feature().string().equals(license2.feature().string())
                    || !license1.subscriptionType().string().equals(license2.subscriptionType().string())
                    || !license1.type().string().equals(license2.type().string())
                    || !license1.issuedTo().equals(license2.issuedTo())
                    || !license1.signature().equals(license2.signature())
                    || license1.expiryDate() != license2.expiryDate()
                    || license1.issueDate() != license2.issueDate()
                    || license1.maxNodes() != license2.maxNodes()) {
                return false;
            }
        }
        return true;
    }
}
