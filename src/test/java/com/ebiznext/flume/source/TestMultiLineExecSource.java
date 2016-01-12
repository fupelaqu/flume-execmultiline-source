/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.ebiznext.flume.source;


import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.flume.*;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.channel.MemoryChannel;
import org.apache.flume.channel.ReplicatingChannelSelector;
import org.apache.flume.conf.Configurables;
import org.apache.flume.lifecycle.LifecycleException;
import org.apache.flume.source.AbstractSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static com.ebiznext.flume.source.MultiLineExecSourceConfigurationConstants.CONFIG_RESTART;
import static com.ebiznext.flume.source.MultiLineExecSourceConfigurationConstants.CONFIG_RESTART_THROTTLE;
import static org.junit.Assert.*;

public class TestMultiLineExecSource {

	private AbstractSource source;

	@Before
	public void setUp() {
		source = new MultiLineExecSource();
	}

	@Test
	public void testProcess() throws InterruptedException, LifecycleException, EventDeliveryException, IOException {
		Channel channel = new MemoryChannel();
		Context context = new Context();

		URL resource = Thread.currentThread().getContextClassLoader().getResource("server.log");
		assertNotNull(resource);

		context.put("command", format("cat %s", resource.getFile()));
//		context.put("regex", ".*(\\s*.*(Exception|Error): .+)|.*(\\s+at .+)|.*(\\s*Caused by:.+)|.*(\\s+... (\\d+) more)");
		context.put("keep-alive", "1");
		context.put("capacity", "100000");
		context.put("transactionCapacity", "100000");
		Configurables.configure(source, context);
		Configurables.configure(channel, context);

		ChannelSelector rcs = new ReplicatingChannelSelector();
		rcs.setChannels(Lists.newArrayList(channel));

		source.setChannelProcessor(new ChannelProcessor(rcs));

		source.start();
		Transaction transaction = channel.getTransaction();

		transaction.begin();
		Event event;

		File actualFile = new File("/tmp/flume-execsource." + Thread.currentThread().getId());
		FileOutputStream outputStream = new FileOutputStream(actualFile);

		while ((event = channel.take()) != null) {
            byte[] bytes = event.getBody();
			outputStream.write(bytes);
			outputStream.write('\n');
		}

		outputStream.close();
		transaction.commit();
		transaction.close();

		source.stop();

		File expectedFile = new File(resource.getFile());

		assertEquals(FileUtils.checksumCRC32(expectedFile), FileUtils.checksumCRC32(actualFile));

		FileUtils.forceDelete(actualFile);
	}

	@Test
	public void testRestart() throws InterruptedException, LifecycleException, EventDeliveryException, IOException {

		Channel channel = new MemoryChannel();
		Context context = new Context();

		context.put(CONFIG_RESTART_THROTTLE, "10");
		context.put(CONFIG_RESTART, "true");

		String expectedString = "flume|#]";
		context.put("command", format("echo %s", expectedString));
		Configurables.configure(source, context);
		Configurables.configure(channel, context);

		ChannelSelector rcs = new ReplicatingChannelSelector();
		rcs.setChannels(Lists.newArrayList(channel));

		source.setChannelProcessor(new ChannelProcessor(rcs));

		source.start();
		Transaction transaction = channel.getTransaction();

		transaction.begin();

		long start = System.currentTimeMillis();

		for (int i = 0; i < 5; i++) {
			Event event = channel.take();
			assertNotNull(event);
			assertNotNull(event.getBody());
			assertEquals(expectedString, new String(event.getBody(), Charsets.UTF_8));
		}

		// ensure restartThrottle was turned down as expected
		assertTrue(System.currentTimeMillis() - start < 10000L);

		transaction.commit();
		transaction.close();

		source.stop();
	}


	/**
	 * Tests to make sure that the shutdown mechanism works. There are races
	 * in this test if the system has another sleep command running with the
	 * same sleep interval but we pick rarely used sleep times and make an
	 * effort to detect if our sleep time is already in use. Note the
	 * ps -ef command should work on both macs and linux.
	 */
	@Test
	public void testShutdown() throws Exception {
		int seconds = 272; // pick a rare sleep time

		// now find one that is not in use
		boolean searchForCommand = true;
		while (searchForCommand) {
			searchForCommand = false;
			String command = "sleep " + seconds;
			Pattern pattern = Pattern.compile("\b" + command + "\b");
			for (String line : exec("ps -ef")) {
				if (pattern.matcher(line).find()) {
					seconds++;
					searchForCommand = true;
					break;
				}
			}
		}

		// yes in the mean time someone could use our sleep time
		// but this should be a fairly rare scenerio

		String command = "sleep " + seconds;
		Pattern pattern = Pattern.compile("\b" + command + "\b");

		Channel channel = new MemoryChannel();
		Context context = new Context();

		context.put(CONFIG_RESTART, "false");

		context.put("command", command);
		Configurables.configure(source, context);
		Configurables.configure(channel, context);

		ChannelSelector rcs = new ReplicatingChannelSelector();
		rcs.setChannels(Lists.newArrayList(channel));

		source.setChannelProcessor(new ChannelProcessor(rcs));
		source.start();
		Thread.sleep(1000L);
		source.stop();
		Thread.sleep(1000L);
		for (String line : exec("ps -ef")) {
			if (pattern.matcher(line).find()) {
				Assert.fail("Found [" + line + "]");
			}
		}
	}

	private static List<String> exec(String command) throws Exception {
		String[] commandArgs = command.split("\\s+");
		Process process = new ProcessBuilder(commandArgs).start();
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(
					  new InputStreamReader(process.getInputStream()));
			List<String> result = Lists.newArrayList();
			String line;
			while ((line = reader.readLine()) != null) {
				result.add(line);
			}
			return result;
		} finally {
			process.destroy();
			if (reader != null) {
				reader.close();
			}
			int exit = process.waitFor();
			if (exit != 0) {
				throw new IllegalStateException("Command [" + command + "] exited with " + exit);
			}
		}
	}
}
