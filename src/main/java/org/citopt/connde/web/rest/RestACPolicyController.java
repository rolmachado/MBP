package org.citopt.connde.web.rest;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.citopt.connde.RestConfiguration;
import org.citopt.connde.domain.access_control.ACAbstractEffect;
import org.citopt.connde.domain.access_control.ACPolicy;
import org.citopt.connde.domain.access_control.IACCondition;
import org.citopt.connde.domain.access_control.dto.ACPolicyRequestDTO;
import org.citopt.connde.domain.device.Device;
import org.citopt.connde.domain.user.User;
import org.citopt.connde.repository.ACConditionRepository;
import org.citopt.connde.repository.ACEffectRepository;
import org.citopt.connde.repository.ACPolicyRepository;
import org.citopt.connde.repository.UserRepository;
import org.citopt.connde.security.SecurityUtils;
import org.citopt.connde.util.C;
import org.citopt.connde.util.Pages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST Controller for managing {@link Device devices}.
 * 
 * @author Jakob Benz
 */
@RestController
@RequestMapping(RestConfiguration.BASE_PATH + "/policy")
@Api(tags = {"Access-Control Policies"})
public class RestACPolicyController {
	
	@Autowired
	private ACPolicyRepository policyRepository;
	
	@Autowired
	private ACConditionRepository conditionRepository;
	
	@Autowired
	private ACEffectRepository effectRepository;

    @Autowired
    private UserRepository userRepository;
    
    
	@GetMapping(produces = "application/hal+json")
	@ApiOperation(value = "Retrieves all existing policies owned by the requesting entity.", produces = "application/hal+json")
	@ApiResponses({ @ApiResponse(code = 200, message = "Success!"), @ApiResponse(code = 404, message = "Requesting user not found!") })
    public ResponseEntity<PagedModel<EntityModel<ACPolicy>>> all(@ApiParam(value = "Page parameters", required = true) Pageable pageable) {
    	// Retrieve requesting user from the database (if it can be identified and is present)
    	User user = userRepository.findOneByUsername(SecurityUtils.getCurrentUserUsername())
    			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Requesting user not found!"));
    	
    	// Retrieve all policies owned by the user (no paging yet)
    	List<ACPolicy> policiesOwnedByUser = policyRepository.findByOwner(user.getId(), Pages.ALL);
    	
    	// Extract requested page from all policies
    	List<ACPolicy> page = Pages.page(policiesOwnedByUser, pageable);
    	
    	// Add self link to every policy
    	List<EntityModel<ACPolicy>> policyEntityModels = page.stream().map(this::policyToEntityModel).collect(Collectors.toList());
    	
    	// Create self link
    	Link link = linkTo(methodOn(getClass()).all(pageable)).withSelfRel();
    	
    	return ResponseEntity.ok(PagedModel.of(policyEntityModels, Pages.metaDataOf(pageable, policyEntityModels.size()), C.listOf(link)));
    }
    
    @GetMapping(path = "/{policyId}", produces = "application/hal+json")
    @ApiOperation(value = "Retrieves an existing policy identified by its id if available for the requesting entity.", produces = "application/hal+json")
    @ApiResponses({ @ApiResponse(code = 200, message = "Success!"), @ApiResponse(code = 401, message = "Not authorized to access the policy!"), @ApiResponse(code = 404, message = "Policy or requesting user not found!") })
    public ResponseEntity<EntityModel<ACPolicy>> one(@PathVariable("policyId") String policyId, @ApiParam(value = "Page parameters", required = true) Pageable pageable) {
    	User user = userRepository.findOneByUsername(SecurityUtils.getCurrentUserUsername())
    			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Requesting user not found!"));
    	
    	// Retrieve the requested policy from the database (if it exists)
    	Optional<ACPolicy> policyOptional = policyRepository.findById(policyId);
    	if (!policyOptional.isPresent()) {
    		return ResponseEntity.notFound().build();
    	}
    	ACPolicy policy = policyOptional.get();
    	
    	// Check whether the requesting user is the owner of the policy
    	if (!policy.getOwner().getId().equals(user.getId())) {
    		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    	}
    			
    	// Add self link to policy
    	EntityModel<ACPolicy> policyEntityModel = policyToEntityModel(policy);
    	
    	return ResponseEntity.ok(policyEntityModel);
    }
    
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/hal+json")
    @ApiOperation(value = "Creates a new policy.", produces = "application/hal+json")
    @ApiResponses({ @ApiResponse(code = 201, message = "Created!"), @ApiResponse(code = 404, message = "Requesting user, condition, or effect not found!"), @ApiResponse(code = 409, message = "Policy name already exists!") })
    public ResponseEntity<EntityModel<ACPolicy>> create(@PathVariable("policyId") String policyId, @Valid @RequestBody ACPolicyRequestDTO requestDto, @ApiParam(value = "Page parameters", required = true) Pageable pageable) {
    	User user = userRepository.findOneByUsername(SecurityUtils.getCurrentUserUsername())
    			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Requesting user not found!"));
    	
    	// Check whether a policy with the same name exists already
    	if (policyRepository.existsByName(requestDto.getName())) {
    		return ResponseEntity.status(HttpStatus.CONFLICT).build();
    	}
    	
    	// Retrieve condition from the database
    	Optional<IACCondition> conditionOptional = conditionRepository.findById(requestDto.getConditionId());
    	if (!conditionOptional.isPresent()) {
    		return ResponseEntity.notFound().build();
    	}
    	IACCondition condition = conditionOptional.get();
    	
    	// Retrieve effects from the database
    	List<ACAbstractEffect<?>> effects = new ArrayList<>();
    	for (String effectId : requestDto.getEffectIds()) {
    		Optional<ACAbstractEffect<?>> effectOptional = effectRepository.findById(effectId);
    		if (!effectOptional.isPresent()) {
    			return ResponseEntity.notFound().build(); 
    		}
    		effects.add(effectOptional.get());
    	}
    	
    	// Create new policy and save it in the database
    	ACPolicy policy = new ACPolicy(requestDto.getName(), requestDto.getPriority(), requestDto.getAccessTypes(), condition, effects, user);
    	policy = policyRepository.save(policy);
    	
    	// Add self link to policy
    	EntityModel<ACPolicy> policyEntityModel = policyToEntityModel(policy);
    	
    	return ResponseEntity.status(HttpStatus.CREATED).body(policyEntityModel); 
    }
    
    private EntityModel<ACPolicy> policyToEntityModel(ACPolicy policy) {
    	return EntityModel.of(policy).add(linkTo(getClass()).slash(policy.getId()).withSelfRel());
    }

}