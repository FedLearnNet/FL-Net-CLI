package bio.cosy.flnet.cli.platform.config;

import bio.cosy.flnet.cli.helper.QuarkusMappingConfig;
import bio.cosy.flnet.cli.helper.WebAddress;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(config = QuarkusMappingConfig.class)
public interface PlatformConfigMapper {

    @Mapping(target = "nginxPort", source = "port")
    void updateFromOptions(PlatformConfig options, @MappingTarget PersistentPlatformConfig target);

    default WebAddress toWebAddress(String value) {
        return value == null ? null : WebAddress.parse(value);
    }
}
