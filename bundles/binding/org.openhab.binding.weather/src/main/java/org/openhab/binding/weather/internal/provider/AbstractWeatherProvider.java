/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.weather.internal.provider;

import org.apache.commons.lang.StringUtils;

import org.openhab.binding.weather.internal.common.LocationConfig;
import org.openhab.binding.weather.internal.common.ProviderConfig;
import org.openhab.binding.weather.internal.common.WeatherConfig;
import org.openhab.binding.weather.internal.common.WeatherContext;
import org.openhab.binding.weather.internal.model.Forecast;
import org.openhab.binding.weather.internal.model.ProviderName;
import org.openhab.binding.weather.internal.model.Weather;
import org.openhab.binding.weather.internal.parser.WeatherParser;

import org.openhab.io.net.http.HttpUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;

/**
 * Common base class for all weather providers. Retrieves, parses and returns
 * weather data.
 *
 * @author Gerhard Riegler
 * @author Christoph Weitkamp - Replaced org.apache.commons.httpclient with HttpUtil
 * @since 1.6.0
 */
public abstract class AbstractWeatherProvider implements WeatherProvider {
    private static final Logger logger = LoggerFactory.getLogger(AbstractWeatherProvider.class);
    private WeatherConfig config = WeatherContext.getInstance().getConfig();
    private WeatherParser parser;

    public AbstractWeatherProvider(WeatherParser parser) {
        this.parser = parser;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Weather getWeather(LocationConfig locationConfig) throws Exception {
        Weather weather = new Weather(getProviderName());
        executeRequest(weather, prepareUrl(getWeatherUrl(), locationConfig), locationConfig);

        String forecastUrl = getForecastUrl();

        if ((forecastUrl != null) && !weather.hasError()) {
            executeRequest(weather, prepareUrl(forecastUrl, locationConfig), locationConfig);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{}[{}]: {}", getProviderName(), locationConfig.getLocationId(), weather.toString());

            for (Weather fc : weather.getForecast()) {
                logger.debug("{}[{}]: {}", getProviderName(), locationConfig.getLocationId(), fc.toString());
            }
        }

        return weather;
    }

    /**
     * Prepares the provider URL by setting the config.
     */
    private String prepareUrl(String url, LocationConfig locationConfig) {
        ProviderConfig providerConfig = config.getProviderConfig(getProviderName());

        if (providerConfig != null) {
            url = StringUtils.replace(url, "[API_KEY]", providerConfig.getApiKey());
            url = StringUtils.replace(url, "[API_KEY_2]", providerConfig.getApiKey2());
        }

        if (locationConfig.getLatitude() != null) {
            url = StringUtils.replace(url, "[LATITUDE]", locationConfig.getLatitude().toString());
        }

        if (locationConfig.getLongitude() != null) {
            url = StringUtils.replace(url, "[LONGITUDE]", locationConfig.getLongitude().toString());
        }

        if (locationConfig.getMeasurementUnits() != null) {
            url = StringUtils.replace(url, "[UNITS]", locationConfig.getMeasurementUnits());
        }

        url = StringUtils.replace(url, "[LANGUAGE]", locationConfig.getLanguage());

        return url;
    }

    /**
     * Executes the http request and parses the returned stream.
     */
    private void executeRequest(Weather weather, String url, LocationConfig locationConfig) throws Exception {
        try {
            logger.trace("{}[{}]: request : {}", getProviderName(), locationConfig.getLocationId(), url);

            String response = StringUtils.trimToEmpty(HttpUtil.executeUrl("GET", url, 15000));

            /**
             * special handling because of identical current and forecast json structure
             * if 'url' contains "forecast" replace data key with forecast
             */
            if ((weather.getProvider() == ProviderName.WEATHERBIT) && StringUtils.contains(url, "forecast/daily")) {
                // replace data with forecast
                response = StringUtils.replace(response, "data", "forecast");
            }

            if (logger.isTraceEnabled()) {
                response = StringUtils.remove(response, "\n");
                response = StringUtils.trim(response);
                logger.trace("{}[{}]: response: {}", getProviderName(), locationConfig.getLocationId(), response);
            }

            if (!response.isEmpty()) {
                parser.parseInto(response, weather);
            }

            // special handling because of bad OpenWeatherMap json structure
            if (weather.getProvider() == ProviderName.OPENWEATHERMAP && weather.getResponseCode() != null
                    && weather.getResponseCode() == 200) {
                weather.setError(null);
            }

            if (!weather.hasError() && response.isEmpty()) {
                weather.setError("Error: response is empty!");
            }

            if (weather.hasError()) {
                logger.error("{}[{}]: Can't retrieve weather data: {}", getProviderName(),
                        locationConfig.getLocationId(), weather.getError());
            } else {
                setLastUpdate(weather);
            }
        } catch (Exception ex) {
            logger.error(getProviderName() + ": " + ex.getMessage());
            weather.setError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Sets the current timestamp in every weather object.
     */
    private void setLastUpdate(Weather weather) {
        Calendar cal = Calendar.getInstance();
        weather.getCondition().setLastUpdate(cal);

        for (Forecast forecast : weather.getForecast()) {
            forecast.getCondition().setLastUpdate(cal);
        }
    }

    /**
     * Returns the provider weather url.
     */
    protected abstract String getWeatherUrl();

    /**
     * Returns the provider forecast url, some providers needs a second http
     * request.
     */
    protected String getForecastUrl() {
        return null;
    }
}
