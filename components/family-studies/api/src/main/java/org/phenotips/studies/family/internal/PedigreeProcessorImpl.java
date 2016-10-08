/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.studies.family.internal;

import org.phenotips.studies.family.Pedigree;
import org.phenotips.studies.family.PedigreeProcessor;
import org.phenotips.vocabulary.Vocabulary;
import org.phenotips.vocabulary.VocabularyTerm;

import org.xwiki.component.annotation.Component;

import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

/**
 * Converts the JSON generated by the pedigree into the default format accepted by PhenoTips.
 *
 * @version $Id$
 * @since 1.2RC1
 */
@Component
public class PedigreeProcessorImpl implements PedigreeProcessor
{
    private static final String PATIENT_JSON_KEY_FEATURES = "features";
    private static final String PEDIGREE_JSON_KEY_FEATURES = PATIENT_JSON_KEY_FEATURES;

    private static final String PATIENT_JSON_KEY_NON_STANDARD_FEATURES = "nonstandard_features";
    private static final String PEDIGREE_JSON_KEY_NON_STANDARD_FEATURES = PATIENT_JSON_KEY_NON_STANDARD_FEATURES;

    private static final String PATIENT_JSON_KEY_GENES = "genes";
    private static final String PEDIGREE_JSON_KEY_GENES = PATIENT_JSON_KEY_GENES;

    private static final String PATIENT_JSON_KEY_FAMILY_HISTORY = "family_history";
    private static final String PEDIGREE_JSON_KEY_FAMILY_HISTORY = PATIENT_JSON_KEY_FAMILY_HISTORY;

    @Inject
    private Logger logger;

    @Inject
    @Named("hpo")
    private Vocabulary hpoService;

    @Inject
    @Named("omim")
    private Vocabulary omimService;

    /**
     * Returns a list of Phenotips JSONs for each patient found in pedigree.
     *
     * @param pedigree a Pedigree object
     * @return a list of patient JSONs. if pedigree is not valid returns an empty list.
     */
    @Override
    public List<JSONObject> convert(Pedigree pedigree)
    {
        List<JSONObject> convertedPatients = new LinkedList<>();

        if (pedigree != null) {
            JSONObject data = pedigree.getData();

            String versionKey = "JSON_version";
            if (data.has(versionKey)
                && !StringUtils.equalsIgnoreCase(data.getString(versionKey), "1.0"))
            {
                this.logger.warn("The version of the pedigree JSON differs from the expected.");
            }

            List<JSONObject> patientJson = pedigree.extractPatientJSONProperties();

            for (JSONObject singlePatient : patientJson) {
                convertedPatients.add(patientJsonToObject(singlePatient));
            }
        }

        return convertedPatients;
    }

    private JSONObject patientJsonToObject(JSONObject externalPatient)
    {
        JSONObject phenotipsPatient = new JSONObject();

        try {
            phenotipsPatient = exchangeIds(externalPatient, phenotipsPatient, this.logger);
            phenotipsPatient = exchangeBasicPatientData(externalPatient, phenotipsPatient);
            phenotipsPatient = exchangeLifeStatus(externalPatient, phenotipsPatient);
            phenotipsPatient = exchangeDates(externalPatient, phenotipsPatient, this.logger);
            phenotipsPatient = exchangePhenotypes(externalPatient, phenotipsPatient, this.hpoService, this.logger);
            phenotipsPatient = exchangeDisorders(externalPatient, phenotipsPatient, this.omimService, this.logger);
            phenotipsPatient = exchangeFamilyHistory(externalPatient, phenotipsPatient);
            phenotipsPatient = exchangeGenes(externalPatient, phenotipsPatient);
        } catch (Exception ex) {
            this.logger.error("Could not convert patient: {}", ex.getMessage());
        }

        return phenotipsPatient;
    }

    private static JSONObject exchangeFamilyHistory(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON)
    {
        phenotipsPatientJSON.put(PATIENT_JSON_KEY_FAMILY_HISTORY,
                                 pedigreePatient.opt(PEDIGREE_JSON_KEY_FAMILY_HISTORY));
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeIds(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON, Logger logger)
    {
        String patientID = "phenotipsId";
        String externalID = "externalID";
        try {
            if (pedigreePatient.has(patientID)) {
                phenotipsPatientJSON.put("id", pedigreePatient.getString(patientID));
            }
            if (pedigreePatient.has(externalID)) {
                phenotipsPatientJSON.put("external_id", pedigreePatient.getString(externalID));
            }
        } catch (Exception ex) {
            logger.error("Could not convert patient IDs: {}", ex.getMessage());
        }
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeBasicPatientData(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON)
    {
        JSONObject name = new JSONObject();
        name.put("first_name", pedigreePatient.opt("fName"));
        name.put("last_name", pedigreePatient.opt("lName"));

        phenotipsPatientJSON.put("sex", pedigreePatient.opt("gender"));
        phenotipsPatientJSON.put("patient_name", name);
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeLifeStatus(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON)
    {
        String pedigreeLifeStatus = pedigreePatient.optString("lifeStatus", "alive");

        String lifeStatus = "alive";
        if (!StringUtils.equalsIgnoreCase(pedigreeLifeStatus, "alive")) {
            lifeStatus = "deceased";
        }

        phenotipsPatientJSON.put("life_status", lifeStatus);
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeDates(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON, Logger logger)
    {
        String dob = "dob";
        String dod = "dod";
        if (pedigreePatient.has(dob)) {
            try {
                phenotipsPatientJSON.put("date_of_birth",
                    PedigreeProcessorImpl.pedigreeDateToDate(pedigreePatient.getJSONObject(dob)));
            } catch (Exception ex) {
                // may happen if date JSON is incorrectly formatted - more likely to happen
                // than in other parts of JSON since we are debating how dates should be stored
                logger.error("Could not convert date of birth: {}", ex.getMessage());
            }
        }
        if (pedigreePatient.has(dod)) {
            try {
                phenotipsPatientJSON.put("date_of_death",
                    PedigreeProcessorImpl.pedigreeDateToDate(pedigreePatient.getJSONObject(dod)));
            } catch (Exception ex) {
                logger.error("Could not convert date of death: {}", ex.getMessage());
            }
        }
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangePhenotypes(JSONObject pedigreePatient,
        JSONObject phenotipsPatientJSON, Vocabulary hpoService, Logger logger)
    {
        JSONArray pedigreeFeatures = pedigreePatient.optJSONArray(PEDIGREE_JSON_KEY_FEATURES);
        if (pedigreeFeatures != null) {
            phenotipsPatientJSON.put(PATIENT_JSON_KEY_FEATURES, pedigreeFeatures);
        }

        JSONArray pedigreeNonStdFeatures = pedigreePatient.optJSONArray(PEDIGREE_JSON_KEY_NON_STANDARD_FEATURES);
        if (pedigreeNonStdFeatures != null) {
            phenotipsPatientJSON.put(PATIENT_JSON_KEY_NON_STANDARD_FEATURES, pedigreeNonStdFeatures);
        }

        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeDisorders(JSONObject pedigreePatient,
        JSONObject phenotipsPatientJSON, Vocabulary omimService, Logger logger)
    {
        String disordersKey = "disorders";
        JSONArray internalTerms = new JSONArray();
        JSONArray externalTerms = pedigreePatient.optJSONArray(disordersKey);

        if (externalTerms != null) {
            for (Object termIdObj : externalTerms) {
                try {
                    VocabularyTerm term = omimService.getTerm(termIdObj.toString());
                    if (term != null) {
                        internalTerms.put(term.toJSON());
                    }
                } catch (Exception ex) {
                    logger.error("Could not convert disorder {} from pedigree JSON to patient JSON", termIdObj);
                }
            }
        }

        phenotipsPatientJSON.put(disordersKey, internalTerms);
        return phenotipsPatientJSON;
    }

    private static JSONObject exchangeGenes(JSONObject pedigreePatient, JSONObject phenotipsPatientJSON)
    {
        JSONArray pedigreeGenes = pedigreePatient.optJSONArray(PEDIGREE_JSON_KEY_GENES);
        if (pedigreeGenes != null) {
            phenotipsPatientJSON.put(PATIENT_JSON_KEY_GENES, pedigreeGenes);
        }
        return phenotipsPatientJSON;
    }

    /**
     * Used for converting a pedigree JSON date to a PhenoTips JSON date.
     *
     * @param pedigreeDate cannot be null.
     */
    private static JSONObject pedigreeDateToDate(JSONObject pedigreeDate)
    {
        return pedigreeDate;
    }
}
