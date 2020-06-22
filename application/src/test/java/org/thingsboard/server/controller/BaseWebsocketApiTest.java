/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.DeviceTypeFilter;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.service.telemetry.cmd.TelemetryPluginCmdsWrapper;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataUpdate;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityHistoryCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.LatestValueCmd;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class BaseWebsocketApiTest extends AbstractWebsocketTest {

    private Tenant savedTenant;
    private User tenantAdmin;
    private TbTestWebSocketClient wsClient;

    @Autowired
    private TimeseriesService tsService;

    @Before
    public void beforeTest() throws Exception {
        loginSysAdmin();

        Tenant tenant = new Tenant();
        tenant.setTitle("My tenant");
        savedTenant = doPost("/api/tenant", tenant, Tenant.class);
        Assert.assertNotNull(savedTenant);

        tenantAdmin = new User();
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setTenantId(savedTenant.getId());
        tenantAdmin.setEmail("tenant2@thingsboard.org");
        tenantAdmin.setFirstName("Joe");
        tenantAdmin.setLastName("Downs");

        tenantAdmin = createUserAndLogin(tenantAdmin, "testPassword1");

        wsClient = buildAndConnectWebSocketClient();
    }

    @After
    public void afterTest() throws Exception {
        wsClient.close();

        loginSysAdmin();

        doDelete("/api/tenant/" + savedTenant.getId().getId().toString())
                .andExpect(status().isOk());
    }

    @Test
    public void testEntityDataHistoryWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        EntityHistoryCmd historyCmd = new EntityHistoryCmd();
        historyCmd.setKeys(Arrays.asList("temperature"));
        historyCmd.setAgg(Aggregation.NONE);
        historyCmd.setLimit(1000);
        historyCmd.setStartTs(now - TimeUnit.HOURS.toMillis(1));
        historyCmd.setEndTs(now);
        EntityDataCmd cmd = new EntityDataCmd(1, edq, historyCmd, null, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertEquals(0, pageData.getData().get(0).getTimeseries().get("temperature").length);

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        TsKvEntry dataPoint2 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(2), new LongDataEntry("temperature", 42L));
        TsKvEntry dataPoint3 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(3), new LongDataEntry("temperature", 42L));
        tsService.save(device.getTenantId(), device.getId(), Arrays.asList(dataPoint1, dataPoint2, dataPoint3), 0).get();

        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        TsValue[] tsArray = pageData.getData().get(0).getTimeseries().get("temperature");
        Assert.assertEquals(3, tsArray.length);
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsArray[0]);
        Assert.assertEquals(new TsValue(dataPoint2.getTs(), dataPoint2.getValueAsString()), tsArray[1]);
        Assert.assertEquals(new TsValue(dataPoint3.getTs(), dataPoint3.getValueAsString()), tsArray[2]);
    }

    @Test
    @Ignore
    public void testEntityDataLatestWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LatestValueCmd latestCmd = new LatestValueCmd();
        latestCmd.setKeys(Collections.singletonList(new EntityKey(EntityKeyType.TIME_SERIES, "temperature")));
        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, latestCmd, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNull(pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature"));

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        tsService.save(device.getTenantId(), device.getId(), Arrays.asList(dataPoint1), 0).get();

        cmd = new EntityDataCmd(2, edq, null, latestCmd, null);

        wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(2, update.getCmdId());
        pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES));
        TsValue tsValue = pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature");
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsValue);
    }

}