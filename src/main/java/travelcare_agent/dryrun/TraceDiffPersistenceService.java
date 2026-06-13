package travelcare_agent.dryrun;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.dryrun.entity.TraceDiff;
import travelcare_agent.dryrun.repository.TraceDiffRepository;

@Service
public class TraceDiffPersistenceService {
    private final TraceDiffRepository repository;
    public TraceDiffPersistenceService(TraceDiffRepository repository){this.repository=repository;}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public TraceDiff save(TraceDiff value){return repository.save(value);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true) public TraceDiff find(String original,String dryRun){return repository.find(original,dryRun).orElseThrow();}
}
