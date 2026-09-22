package bio.cosy.flnet.cli.client.config;

import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.helper.QuarkusMappingConfig;
import bio.cosy.flnet.cli.helper.WebAddress;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(config = QuarkusMappingConfig.class)
public interface ClientConfigMapper {

    @Mapping(target = "platformAddress", source = "platformUrl")
    @Mapping(target = "keycloakRealmPath", ignore = true)
    @Mapping(target = "platformPassword", ignore = true)
    void updateFromOptions(ClientConfig options, @MappingTarget PersistentClientConfig target);

    default WebAddress toWebAddress(String value) {
        return value == null ? null : WebAddress.parse(value);
    }

    default AutoAccess toAutoAccess(String value) {
        return AutoAccess.parse(value, AutoAccess.NONE);
    }
}
