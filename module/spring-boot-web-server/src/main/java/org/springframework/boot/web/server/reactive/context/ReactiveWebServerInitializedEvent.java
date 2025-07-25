/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.server.reactive.context;

import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

/**
 * Event to be published after the {@link WebServer} is ready. Useful for obtaining the
 * local port of a running server.
 *
 * @author Brian Clozel
 * @author Stephane Nicoll
 * @since 2.0.0
 */
public class ReactiveWebServerInitializedEvent extends WebServerInitializedEvent {

	private final ReactiveWebServerApplicationContext applicationContext;

	public ReactiveWebServerInitializedEvent(WebServer webServer,
			ReactiveWebServerApplicationContext applicationContext) {
		super(webServer);
		this.applicationContext = applicationContext;
	}

	@Override
	public ReactiveWebServerApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

}
