/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.muzima.handler;

import net.minidev.json.JSONArray;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.annotation.Handler;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.module.idgen.service.IdentifierSourceService;
import org.openmrs.module.muzima.api.service.RegistrationDataService;
import org.openmrs.module.muzima.exception.QueueProcessorException;
import org.openmrs.module.muzima.model.QueueData;
import org.openmrs.module.muzima.model.RegistrationData;
import org.openmrs.module.muzima.model.handler.QueueDataHandler;
import org.openmrs.module.muzima.utils.JsonUtils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * TODO: Write brief description about the class here.
 */
@Handler(supports = QueueData.class, order = 1)
public class JsonRegistrationQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "json-registration";

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    private final Log log = LogFactory.getLog(JsonRegistrationQueueDataHandler.class);

    private Patient unsavedPatient;
    private String payload;
    Set<PersonAttribute> personAttributes;
    private QueueProcessorException queueProcessorException;

    public static final String NEXT_OF_KIN_ADDRESS = "b5c2765a-73c9-439e-92be-3b42724f02c6";
    public static final String NEXT_OF_KIN_CONTACT = "5c9f67cb-d133-45a6-a573-512b71b625a0";
    public static final String NEXT_OF_KIN_NAME = "29674853-1805-486c-a183-0b82ebb9ece3";
    public static final String NEXT_OF_KIN_RELATIONSHIP = "e83bf759-9ecf-4597-acb6-6ae92844c6f0";
    public static final String SUBCHIEF_NAME = "9e66c339-fd18-40e8-b133-7a4207e3d616";
    public static final String TELEPHONE_CONTACT = "86e70608-1486-4098-88e7-4324faf722f7";
    public static final String EMAIL_ADDRESS = "ad957d51-4ce2-4a49-be14-81dd6f71ef82";
    public static final String ALTERNATE_PHONE_CONTACT = "059491b4-fe25-4463-9b58-6b49de9b5ec5";
    public static final String NEAREST_HEALTH_CENTER = "8d87236c-c2cc-11de-8d13-0010c6dffd0f";
    public static final String GUARDIAN_FIRST_NAME = "c6cc624c-962c-41b4-95cd-8c13bf17ebce";
    public static final String GUARDIAN_LAST_NAME = "376a2d8d-f83e-4e6b-a4aa-2001256a6267";

    @Override
    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            if (validate(queueData)) {
                registerUnsavedPatient();
            }
        } catch (Exception e) {
            /*Custom exception thrown by the validate function should not be added again into @queueProcessorException.
             It should add the runtime dao Exception while saving the data into @queueProcessorException collection */
            if (!e.getClass().equals(QueueProcessorException.class)) {
                queueProcessorException.addException(e);
            }
        } finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    @Override
    public boolean validate(QueueData queueData) {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            payload = queueData.getPayload();
            unsavedPatient = new Patient();
            populateUnsavedPatientFromPayload();
            validateUnsavedPatient();
            return true;
        } catch (Exception e) {
            queueProcessorException.addException(e);
            return false;
        } finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    @Override
    public String getDiscriminator() {
        return DISCRIMINATOR_VALUE;
    }

    private void validateUnsavedPatient() {
        Patient savedPatient = findSimilarSavedPatient();
        if (savedPatient != null) {
            queueProcessorException.addException(
                    new Exception(
                            "Found a patient with similar characteristic :  patientId = " + savedPatient.getPatientId()
                                    + " Identifier Id = " + savedPatient.getPatientIdentifier().getIdentifier()
                    )
            );
        }
    }

    private void populateUnsavedPatientFromPayload() {
        setPatientIdentifiersFromPayload();
        setPatientBirthDateFromPayload();
        setPatientBirthDateEstimatedFromPayload();
        setPatientGenderFromPayload();
        setPatientNameFromPayload();
        setPatientAddressesFromPayload();
        setPersonAttributesFromPayload();
    }

    private void setPatientIdentifiersFromPayload() {
        Set<PatientIdentifier> patientIdentifiers = new HashSet<PatientIdentifier>();
        PatientIdentifier preferredIdentifier = getPreferredPatientIdentifierFromPayload();
        if (preferredIdentifier != null) {
            patientIdentifiers.add(preferredIdentifier);
        }
        List<PatientIdentifier> otherIdentifiers = getOtherPatientIdentifiersFromPayload();
        if (!otherIdentifiers.isEmpty()) {
            patientIdentifiers.addAll(otherIdentifiers);
        }
        setIdentifierTypeLocation(patientIdentifiers);
        unsavedPatient.setIdentifiers(patientIdentifiers);
    }

    private PatientIdentifier getPreferredPatientIdentifierFromPayload(){
        String identifierValue = JsonUtils.readAsString(payload, "$['patient']['patient.medical_record_number']");
        String identifierTypeName = "HTS ID";

        PatientIdentifier preferredPatientIdentifier = createPatientIdentifier(identifierTypeName, identifierValue);
        if (preferredPatientIdentifier != null) 
		{
            preferredPatientIdentifier.setPreferred(true);
            return preferredPatientIdentifier;
        } 
		else 
		{
            return null;
        }
    }

    private List<PatientIdentifier> getOtherPatientIdentifiersFromPayload() {
        List<PatientIdentifier> otherIdentifiers = new ArrayList<PatientIdentifier>();

        // add OpenMRS ID to the list. The system requires this in order to create new patient
        otherIdentifiers.add(generateOpenMRSID());
        Object identifierTypeNameObject = JsonUtils.readAsObject(payload, "$['observation']['other_identifier_type']");
        Object identifierValueObject =JsonUtils.readAsObject(payload, "$['observation']['other_identifier_value']");

        if (identifierTypeNameObject instanceof JSONArray) {
            JSONArray identifierTypeName = (JSONArray) identifierTypeNameObject;
            JSONArray identifierValue = (JSONArray) identifierValueObject;
            for (int i = 0; i < identifierTypeName.size(); i++) {
                PatientIdentifier identifier = createPatientIdentifier(identifierTypeName.get(i).toString(),
                        identifierValue.get(i).toString());
                if (identifier != null) {
                    otherIdentifiers.add(identifier);
                }
            }
        } else if (identifierTypeNameObject instanceof String) {
            String identifierTypeName = (String) identifierTypeNameObject;
            String identifierValue = (String) identifierValueObject;
            PatientIdentifier identifier = createPatientIdentifier(identifierTypeName, identifierValue);
            if (identifier != null) {
                otherIdentifiers.add(identifier);
            }
        }
        return otherIdentifiers;
    }

    private PatientIdentifier createPatientIdentifier(String identifierTypeName, String identifierValue) {
        PatientIdentifierType identifierType = Context.getPatientService()
                .getPatientIdentifierTypeByName(identifierTypeName);
        if (identifierType == null) {
            queueProcessorException.addException(
                    new Exception("Unable to find identifier type with name: " + identifierTypeName));
        } else if (identifierValue == null) {
            queueProcessorException.addException(
                    new Exception("Identifier value can't be null type: " + identifierTypeName));
        } else {
            PatientIdentifier patientIdentifier = new PatientIdentifier();
            patientIdentifier.setIdentifierType(identifierType);
            patientIdentifier.setIdentifier(identifierValue);
            return patientIdentifier;
        }
        return null;
    }

    private void setIdentifierTypeLocation(final Set<PatientIdentifier> patientIdentifiers) {
        String locationIdString = JsonUtils.readAsString(payload, "$['encounter']['encounter.location_id']");
        Location location = null;
        int locationId;

        if(locationIdString != null){
            locationId = Integer.parseInt(locationIdString);
            location = Context.getLocationService().getLocation(locationId);
        }

        if (location == null) {
            queueProcessorException.addException(
                    new Exception("Unable to find encounter location using the id: " + locationIdString));
        } else {
            Iterator<PatientIdentifier> iterator = patientIdentifiers.iterator();
            while (iterator.hasNext()) {
                PatientIdentifier identifier = iterator.next();
                identifier.setLocation(location);
            }
        }
    }

    private void setPatientBirthDateFromPayload(){
        Date birthDate = JsonUtils.readAsDate(payload, "$['patient']['patient.birth_date']");
        unsavedPatient.setBirthdate(birthDate);
    }

    private void setPatientBirthDateEstimatedFromPayload(){
        boolean birthdateEstimated = JsonUtils.readAsBoolean(payload, "$['patient']['patient.birthdate_estimated']");
        unsavedPatient.setBirthdateEstimated(birthdateEstimated);
    }

    private void setPatientGenderFromPayload(){
        String gender = JsonUtils.readAsString(payload, "$['patient']['patient.sex']");
        unsavedPatient.setGender(gender);
    }

    private void setPatientNameFromPayload(){
        String givenName = JsonUtils.readAsString(payload, "$['patient']['patient.given_name']");
        String familyName = JsonUtils.readAsString(payload, "$['patient']['patient.family_name']");
        String middleName="";
        try{
            middleName= JsonUtils.readAsString(payload, "$['patient']['patient.middle_name']");
        } catch(Exception e){
            log.error(e);
        }

        PersonName personName = new PersonName();
        personName.setGivenName(givenName);
        personName.setMiddleName(middleName);
        personName.setFamilyName(familyName);
        unsavedPatient.addName(personName);
    }

    private void registerUnsavedPatient() {
        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);
        String temporaryUuid = getPatientUuidFromPayload();
        RegistrationData registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
        if (registrationData == null) {
            registrationData = new RegistrationData();
            registrationData.setTemporaryUuid(temporaryUuid);
            Context.getPatientService().savePatient(unsavedPatient);
            String assignedUuid = unsavedPatient.getUuid();
            registrationData.setAssignedUuid(assignedUuid);
            registrationDataService.saveRegistrationData(registrationData);
        }
    }

    private String getPatientUuidFromPayload(){
        return JsonUtils.readAsString(payload, "$['patient']['patient.uuid']");
    }

    private void setPatientAddressesFromPayload(){
        PersonAddress patientAddress = new PersonAddress();

        String county = JsonUtils.readAsString(payload, "$['patient']['patient.county']");
        patientAddress.setCountyDistrict(county);

        String subCounty = JsonUtils.readAsString(payload, "$['patient']['patient.sub_county']");
        patientAddress.setStateProvince(subCounty);

        String ward = JsonUtils.readAsString(payload, "$['patient']['patient.ward']");
        patientAddress.setCountyDistrict(ward);

        String location = JsonUtils.readAsString(payload, "$['patient']['patient.location']");
        patientAddress.setAddress6(location);

        String sub_location = JsonUtils.readAsString(payload, "$['patient']['patient.sub_location']");
        patientAddress.setAddress5(sub_location);

        String village = JsonUtils.readAsString(payload, "$['patient']['patient.village']");
        patientAddress.setCityVillage(village);

        String postal_address = JsonUtils.readAsString(payload, "$['patient']['patient.postal_address']");
        patientAddress.setAddress1(postal_address);

        String landmark = JsonUtils.readAsString(payload, "$['patient']['patient.landmark']");
        patientAddress.setAddress2(landmark);

        Set<PersonAddress> addresses = new TreeSet<PersonAddress>();
        addresses.add(patientAddress);
        unsavedPatient.setAddresses(addresses);

    }

    private void setPersonAttributesFromPayload(){
        personAttributes = new TreeSet<PersonAttribute>();
        PersonService personService = Context.getPersonService();

        String mothersName = JsonUtils.readAsString(payload, "$['patient']['patient.mothers_name']");
        setAsAttribute("Mother's Name",mothersName);

        String phoneNumber = JsonUtils.readAsString(payload, "$['patient']['patient.phone_number']");
        setAsAttributeByUUID(TELEPHONE_CONTACT,phoneNumber);

        String nearestHealthCenter = JsonUtils.readAsString(payload, "$['patient']['patient.nearest_health_center']");
        setAsAttributeByUUID(NEAREST_HEALTH_CENTER,nearestHealthCenter);

        String emailAddress = JsonUtils.readAsString(payload, "$['patient']['patient.email_address']");
        setAsAttributeByUUID(EMAIL_ADDRESS,emailAddress);

        String guardianFirstName = JsonUtils.readAsString(payload, "$['patient']['patient.guardian_first_name']");
        setAsAttributeByUUID(GUARDIAN_FIRST_NAME,guardianFirstName);

        String guardianLastName = JsonUtils.readAsString(payload, "$['patient']['patient.guardian_last_name']");
        setAsAttributeByUUID(GUARDIAN_LAST_NAME,guardianLastName);

        String alternativePhoneContact = JsonUtils.readAsString(payload, "$['patient']['patient.alternate_phone_contact']");
        setAsAttributeByUUID(ALTERNATE_PHONE_CONTACT,alternativePhoneContact);

        String nextOfKinName = JsonUtils.readAsString(payload, "$['patient']['patient.next_of_kin_name']");
        setAsAttributeByUUID(NEXT_OF_KIN_NAME,nextOfKinName);

        String nextOfKinRelationship = JsonUtils.readAsString(payload, "$['patient']['patient.next_of_kin_relationship']");
        setAsAttributeByUUID(NEXT_OF_KIN_RELATIONSHIP,nextOfKinRelationship);

        String nextOfKinContact = JsonUtils.readAsString(payload, "$['patient']['patient.next_of_kin_contact']");
        setAsAttributeByUUID(NEXT_OF_KIN_CONTACT,nextOfKinContact);

        String nextOfKinAddress = JsonUtils.readAsString(payload, "$['patient']['patient.next_of_kin_address']");
        setAsAttributeByUUID(NEXT_OF_KIN_ADDRESS,nextOfKinAddress);

        unsavedPatient.setAttributes(personAttributes);
    }

    private void setAsAttribute(String attributeTypeName, String value){
        PersonService personService = Context.getPersonService();
        PersonAttributeType attributeType = personService.getPersonAttributeTypeByName(attributeTypeName);
        if(attributeType !=null && value != null){
            PersonAttribute personAttribute = new PersonAttribute(attributeType, value);
            personAttributes.add(personAttribute);
        } else if(attributeType ==null){
            queueProcessorException.addException(
                    new Exception("Unable to find Person Attribute type by name '" + attributeTypeName + "'")
            );
        }
    }

    private void setAsAttributeByUUID(String uuid, String value){
        PersonService personService = Context.getPersonService();
        PersonAttributeType attributeType = personService.getPersonAttributeTypeByUuid(uuid);
        if(attributeType !=null && value != null){
            PersonAttribute personAttribute = new PersonAttribute(attributeType, value);
            personAttributes.add(personAttribute);
        } else if(attributeType ==null){
            queueProcessorException.addException(
                    new Exception("Unable to find Person Attribute type by uuid '" + uuid + "'")
            );
        }
    }

    private Patient findSimilarSavedPatient() {
        Patient savedPatient = null;
        if (unsavedPatient.getNames().isEmpty()) {
            PatientIdentifier identifier = unsavedPatient.getPatientIdentifier();
            if (identifier != null) {
                List<Patient> patients = Context.getPatientService().getPatients(identifier.getIdentifier());
                savedPatient = findPatient(patients, unsavedPatient);
            }
        } else {
            PersonName personName = unsavedPatient.getPersonName();
            List<Patient> patients = Context.getPatientService().getPatients(personName.getFullName());
            savedPatient = findPatient(patients, unsavedPatient);
        }
        return savedPatient;
    }

    private Patient findPatient(final List<Patient> patients, final Patient unsavedPatient) {
        for (Patient patient : patients) {
            // match it using the person name and gender, what about the dob?
            PersonName savedPersonName = patient.getPersonName();
            PersonName unsavedPersonName = unsavedPatient.getPersonName();
            if (StringUtils.isNotBlank(savedPersonName.getFullName())
                    && StringUtils.isNotBlank(unsavedPersonName.getFullName())) {
                if (StringUtils.equalsIgnoreCase(patient.getGender(), unsavedPatient.getGender())) {
                    if (patient.getBirthdate() != null && unsavedPatient.getBirthdate() != null
                            && DateUtils.isSameDay(patient.getBirthdate(), unsavedPatient.getBirthdate())) {
                        String savedGivenName = savedPersonName.getGivenName();
                        String unsavedGivenName = unsavedPersonName.getGivenName();
                        int givenNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedGivenName),
                                StringUtils.lowerCase(unsavedGivenName));
                        String savedFamilyName = savedPersonName.getFamilyName();
                        String unsavedFamilyName = unsavedPersonName.getFamilyName();
                        int familyNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedFamilyName),
                                StringUtils.lowerCase(unsavedFamilyName));
                        if (givenNameEditDistance < 3 && familyNameEditDistance < 3) {
                            return patient;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean accept(final QueueData queueData) {
        return StringUtils.equals(DISCRIMINATOR_VALUE, queueData.getDiscriminator());
    }

    /**
     * Can't save patients unless they have required OpenMRS IDs
     */
    private PatientIdentifier generateOpenMRSID() {
        PatientIdentifierType openmrsIDType = Context.getPatientService().getPatientIdentifierTypeByUuid("8d793bee-c2cc-11de-8d13-0010c6dffd0f");

        String locationIdString = JsonUtils.readAsString(payload, "$['encounter']['encounter.location_id']");
        Location location = null;
        int locationId;

        if(locationIdString != null){
            locationId = Integer.parseInt(locationIdString);
            location = Context.getLocationService().getLocation(locationId);
        }

        String generated = Context.getService(IdentifierSourceService.class).generateIdentifier(openmrsIDType, "Registration");
        PatientIdentifier identifier = new PatientIdentifier(generated, openmrsIDType, location);
        return identifier;
    }

}