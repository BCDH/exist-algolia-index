/*
 * Copyright (C) 2017  Belgrade Center for Digital Humanities
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.storage;

import org.exist.EXistException;
import org.exist.util.Configuration;

import java.util.Observer;
import java.util.Optional;

/**
 * Bridge class to enable access to {@link org.exist.storage.BrokerPools}
 * from Scala
 *
 * @author Adam Retter <adam.retter@googlemail.com>
 */
public class ScalaBrokerPoolBridge {
    public static final String DEFAULT_INSTANCE_NAME = BrokerPool.DEFAULT_INSTANCE_NAME;
    public static final String PROPERTY_DATA_DIR = BrokerPool.PROPERTY_DATA_DIR;

    public static void configure(final String instanceName, final int minBrokers, final int maxBrokers, final Configuration config, final Optional<Observer> statusObserver) throws EXistException {
        BrokerPool.configure(instanceName,minBrokers, maxBrokers, config, statusObserver);
    }

    public static BrokerPool getInstance(final String instanceName) throws EXistException {
        return BrokerPool.getInstance(instanceName);
    }

    public static void release(final BrokerPool brokerPool, final DBBroker broker) {
        brokerPool.release(broker);
    }
}
